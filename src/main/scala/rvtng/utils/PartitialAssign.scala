package rvtng.utils

import chisel3._
import chisel3.util._

object PartitialAssign {
  def apply(a: Bundle, b: Bundle): Bundle = {
    a.elements.foreach({
      case (name, field) =>
        if (b.elements.contains(name)) {
          field := b.elements(name)
        }
    })
    a
  }
}
