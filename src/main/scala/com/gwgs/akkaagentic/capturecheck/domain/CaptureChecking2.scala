package com.gwgs.akkaagentic.capturecheck.domain

import language.experimental.captureChecking

import java.io.FileOutputStream

/**
  * Following https://watch.softinio.com/w/p/1y91HpAp7Nd3WcuAKXpGLz?playlistPosition=8&resume=true
  */
object CaptureChecking2:

  /**
    * op is impure, needs a capturing type FileOutputStream with any capability. The value of this cacapturing type,
    * `logFile`, is created inside `usingLogFile` function scope. So in FileOutputStream^ => T, the param of type
    * FileOutputStream^ can't be used outside of `usingLogFile`.
    */
  def usingLogFile[T](op: FileOutputStream^ => T): T =
    val logFile = FileOutputStream("logs.txt")
    val result = op(logFile)
    logFile.close ()
    result

  // The expression's type () => Unit is not allowed to capture the root capability 'any'
  // This usually means that a capability persists longer than its allowed lifetime.
  //   Found:    (f: java.io.FileOutputStream^?) ->? () ->{f} Unit
  //   Required: java.io.FileOutputStream^ => () ->? Unit
  // val later = usingLogFile { f => () => f.write("hello".getBytes) }


  // A generic try-with-resources like function
  def using[R <: AutoCloseable, T](resource: R^)(op: R^{resource} => T): T =
    try op (resource)
    finally resource.close()

  val content = "hello"
  val written = using(FileOutputStream("log.txt")) {
      r => r.write(content.getBytes())
      content.length()
    }


  class Tracked(override val toString: String = "Tracked")
  class Indirect(val t: Tracked^) extends Tracked(s"Indirect($t)")
  class SubButNot extends Tracked(s"SubButNot")
  case class Tracker(val t: Tracked^)

  def demo(): Unit =
    // capability `t`
    val t: Tracked^ = new Tracked

    val sd = Tracker(t)
    val si = Tracker(new Indirect(t))
    val sn: Tracker^ = Tracker (new SubButNot)
    
    // Function foo needs Tracker captures capability `t`
    def foo(f: Tracker^{t} => Unit): Unit =
      f(sd)
      f(si)
      // sn has no `t``
      // f(sn)
    
    foo(f = a => println(s"$a"))
    