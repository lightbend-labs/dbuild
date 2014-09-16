package com.typesafe.dbuild.plugin

import sbt._


class Stated[A](val state: State, val value: A) {
  val extracted = 
    Project extract state
  
  def runTask[T](task: TaskKey[T]): Stated[T] = 
    Stated tupled (extracted runTask (task, state))

  def get[T](key: SettingKey[T]): Stated[T] =
    Stated(state, extracted get key)
    
  def mergeSettings =
    Stated(state, extracted.session.mergeSettings)
    
  def map[B](f: A => B): Stated[B] =
    Stated(state, f(value))
    
  def of[A](x: A): Stated[A] = Stated(state, x)
    
}

object Stated {
  def apply(state: State): Stated[Unit] = new Stated(state, ())
  def apply[A](state: State, a: A): Stated[A] = new Stated(state, a)
  def tupled[A](value: (State, A)): Stated[A] = new Stated(value._1, value._2)
}