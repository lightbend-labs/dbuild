package com.typesafe.dbuild.model

object ClassLoaderMadness {
  def withContextLoader[A](cl: ClassLoader)(f: => A): A = {
    val current = Thread.currentThread.getContextClassLoader
    try {
      Thread.currentThread setContextClassLoader cl
      f
    } finally Thread.currentThread setContextClassLoader current
  }
}