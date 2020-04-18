import scala.scalajs.js
import js.annotation._
import js.|

package modifiers {

package modifiers {

@js.native
trait IEvent[T] extends js.Object {
}

@js.native
@JSGlobal("modifiers.Emitter")
class Emitter[T] extends js.Object {
  def event: IEvent[T] = js.native
  def fire(event: T = ???): Unit = js.native
  def dispose(): Unit = js.native
}

@js.native
@JSGlobal("modifiers.EditorType")
object EditorType extends js.Object {
  var ICodeEditor: String = js.native
  var IDiffEditor: String = js.native
}

@js.native
@JSGlobal("modifiers.EditorType2")
object EditorType2 extends js.Object {
  var ICodeEditor: String = js.native
  var IDiffEditor: String = js.native
}

@js.native
@JSGlobal("modifiers.CursorMoveByUnit")
object CursorMoveByUnit extends js.Object {
  var Line: String = js.native
  var WrappedLine: String = js.native
  var Character: String = js.native
  var HalfLine: String = js.native
}

@js.native
@JSGlobal("modifiers.Uri")
class Uri extends js.Object {
  def scheme: String = js.native
  def authority: String = js.native
  def path: String = js.native
}

@js.native
@JSGlobal("modifiers.Uri")
object Uri extends js.Object {
  def isUri(thing: js.Any): Boolean = js.native
  def parse(value: String): Uri = js.native
}

@js.native
trait A extends js.Object {
  var public: Boolean = js.native
  var static: Boolean = js.native
  var readonly: Boolean = js.native
  var `abstract`: Boolean = js.native
  var `protected`: Boolean = js.native
}

@js.native
@JSGlobal("modifiers.Foo")
class Foo extends js.Object {
  def token(): String = js.native
  def token(value: String): Unit = js.native
}

@js.native
@JSGlobal("modifiers")
object Modifiers extends js.Object {
  val id: String = js.native
  def name: String = js.native
}

}

}
