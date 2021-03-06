import scalaz.Scalaz._
import scalaz._

abstract class ActorVisitor[Abs : JoinLattice] {
  def send(pos: Position, target: Abs, message: String, args: List[Abs]): Unit
}
class EmptyActorVisitor[Abs : JoinLattice] extends ActorVisitor[Abs] {
  def send(pos: Position, target: Abs, message: String, args: List[Abs]) = ()
}
class RecordActorVisitor[Abs : JoinLattice] extends ActorVisitor[Abs] {
  var targetVals = Map.empty[Position, Abs].withDefaultValue(JoinLattice[Abs].bottom)
  var argsVals = Map.empty[Position, List[Abs]]
  def send(pos: Position, target: Abs, message: String, args: List[Abs]): Unit = {
    targetVals += (pos -> (JoinLattice[Abs].join(targetVals(pos), target)))
    argsVals += (pos -> (argsVals.get(pos) match {
      case Some(vs) if vs.length == args.length => vs.zip(args).map({ case (x, y) => JoinLattice[Abs].join(x, y) })
      case Some(vs) => throw new Error("should not happen")
      case None => args
    }))
  }
  def print = {
    targetVals.foreach({ case (k, v) =>
      println(s"$k: $v")
    })
    println("----------")
    argsVals.foreach({ case (k, v) =>
      println(s"$k: $v")
    })
  }
}

class ASchemeSemantics[Abs : IsASchemeLattice, Addr : Address, Time : Timestamp, PID : ThreadIdentifier]
  (primitives: Primitives[Addr, Abs],
    visitor: ActorVisitor[Abs])
    extends SchemeSemantics[Abs, Addr, Time](primitives) {
  object ActorAction extends ActorActionHelpers[SchemeExp, Abs, Addr, Time, PID]

  case class FrameSendTarget(pos: Position, message: Identifier, args: List[SchemeExp], env: Env) extends SchemeFrame
  case class FrameSend(pos: Position, message: Identifier, target: Abs, argsv: List[Abs], args: List[SchemeExp], env: Env) extends SchemeFrame
  case class FrameCreate(argsv: List[Abs], args: List[SchemeExp], exp: SchemeExp, env: Env) extends SchemeFrame
  case class FrameBecome(argsv: List[Abs], args: List[SchemeExp], env: Env) extends SchemeFrame

  override def atomicEval(e: SchemeExp, env: Env, store: Sto): Option[(Abs, Set[Effect[Addr]])] = e match {
    case a: SchemeActor => Some((IsASchemeLattice[Abs].injectActor[SchemeExp, Addr](a, env), Set()))
    case _ => super.atomicEval(e, env, store)
  }

  override def stepEval(e: SchemeExp, env: Env, store: Sto, t: Time) = optimizeAtomic(e match {
    case a: SchemeActor => Action.value(IsASchemeLattice[Abs].injectActor[SchemeExp, Addr](a, env), store)
    case SchemeSend(target, message, args, pos) => Action.push(FrameSendTarget(pos, message, args, env), target, env, store)
    case SchemeCreate(beh, args, _) => Action.push(FrameCreate(List(), args, beh, env), beh, env, store)
    case SchemeBecome(beh, args, _) => Action.push(FrameBecome(List(), args, env), beh, env, store)
    case SchemeTerminate(_) => ActorAction.terminate
    case _ => super.stepEval(e, env, store, t)
  }, t)

  override def stepReceive(self: Any, mname: String, margsv: List[Abs], actd: SchemeExp, env: Env, store: Sto, t: Time) = actd match {
    case SchemeActor(name, _, defs, _) =>
      defs.get(mname) match {
        case Some((margs, body)) =>
          if (margs.size != margsv.size) {
            Action.error(ArityError(s"receive message $mname on actor $name with pid $self", margs.size, margsv.size))
          } else {
            val (env2, store2) = bindArgs(margs.zip(margsv), env, store, t)
            val pself = self.asInstanceOf[PID]
            val vself = IsASchemeLattice[Abs].injectPid(pself)
            val aself = Address[Addr].variable(Identifier("self", Position.none), vself, t)
            Action.eval(if (body.size == 1) { body.head } else { SchemeBegin(body, body.head.pos) },
              env2.extend("self", aself), store2.extend(aself, vself))
          }
        case None =>
          Action.error(MessageNotSupported(name, mname, defs.keys.toList))
      }
  }

  private def send(pos: Position, target: Abs, message: String, args: List[Abs]): Set[Action[SchemeExp, Abs, Addr]] = {
    visitor.send(pos, target, message, args)
    val pids = IsASchemeLattice[Abs].getPids(target)
    if (pids.isEmpty) {
      Action.error(TypeError("send", "first operand", "pid value", s"non-pid value ($target)"))
    } else {
      pids.map(p => ActorAction.send(p, message, args, IsASchemeLattice[Abs].injectPid(p)))
    }
  }

  override def stepKont(v: Abs, frame: Frame, store: Sto, t: Time) = optimizeAtomic(frame match {
    case FrameSendTarget(pos, message, List(), env) =>
      send(pos, v, message.name, List())
    case FrameSendTarget(pos, message, first :: rest, env) =>
      Action.push(FrameSend(pos, message, v, List(), rest, env), first, env, store)
    case FrameSend(pos, message, target, revargsv, List(), env) =>
      val argsv = (v :: revargsv).reverse
      send(pos, target, message.name, (v :: revargsv).reverse)
    case FrameSend(pos, message, target, argsv, first :: rest, env) =>
      Action.push(FrameSend(pos, message, target, v :: argsv, rest, env), first, env, store)
    case FrameCreate(revargsv, List(), exp, env) =>
      val act :: argsv = (v :: revargsv).reverse
      val actors = IsASchemeLattice[Abs].getActors[SchemeExp, Addr](act)
      if (actors.isEmpty) {
        Action.error(TypeError("create", "first operand", "actor", s"non-actor value ($act)"))
      } else {
        actors.map({ case (actd @ SchemeActor(name, xs, defs, _), env) =>
          if (xs.size != argsv.size) {
            Action.error(ArityError(s"create actor $name", xs.size, argsv.size))
          } else {
            val (env2, store2) = bindArgs(xs.zip(argsv), env, store, t)
            ActorAction.create(name, actd, exp, env2, store2, IsASchemeLattice[Abs].injectPid _)
          }
        })
      }
    case FrameCreate(argsv, first :: rest, exp, env) =>
      Action.push(FrameCreate(v :: argsv, rest, exp, env), first, env, store)
    case FrameBecome(revargsv, List(), env) =>
      val act :: argsv = (v :: revargsv).reverse
      val actors = IsASchemeLattice[Abs].getActors[SchemeExp, Addr](act)
      if (actors.isEmpty) {
        Action.error(TypeError("become", "first operand", "actor", s"non-actor value ($act)"))
      } else {
        actors.map({ case (actd @ SchemeActor(name, xs, defs, _), env) =>
          if (xs.size != argsv.size) {
            Action.error(ArityError("become behavior", xs.size, argsv.size))
          } else {
            val (env2, store2) = bindArgs(xs.zip(argsv), env, store, t)
            ActorAction.become(name, actd, env2, store2, IsASchemeLattice[Abs].inject(false))
          }
        })
      }
    case FrameBecome(argsv, first :: rest, env) =>
      Action.push(FrameBecome(v :: argsv, rest, env), first, env, store)
    case _ => super.stepKont(v, frame, store, t)
  }, t)
}
