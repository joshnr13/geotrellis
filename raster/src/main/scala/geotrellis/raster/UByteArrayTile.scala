package geotrellis.raster

import geotrellis.vector.Extent

import spire.syntax.cfor._
import java.nio.ByteBuffer

/**
 * ArrayTile based on Array[Byte] (each cell as a Byte).
 */
abstract class UByteArrayTile(array: Array[Byte], cols: Int, rows: Int)
    extends MutableArrayTile with IntBasedArrayTile {
  val cellType: UByteCells with NoDataHandling
  def apply(i: Int): Int
  def update(i: Int, z: Int)

  def toBytes: Array[Byte] = array.clone
  def copy = UByteArrayTile(array.clone, cols, rows, cellType)
}

final case class UByteRawArrayTile(array: Array[Byte], val cols: Int, val rows: Int)
    extends UByteArrayTile(array, cols, rows) {
  val cellType = UByteCellType
  def apply(i: Int): Int = array(i) & 0xFF
  def update(i: Int, z: Int) { array(i) = z.toByte }
}

final case class UByteConstantNoDataArrayTile(array: Array[Byte], val cols: Int, val rows: Int)
    extends UByteArrayTile(array, cols, rows) {
  val cellType = UByteConstantNoDataCellType
  def apply(i: Int): Int = ub2i(array(i))
  def update(i: Int, z: Int) { array(i) = i2ub(z) }
}

final case class UByteUserDefinedNoDataArrayTile(array: Array[Byte], val cols: Int, val rows: Int, val cellType: UByteUserDefinedNoDataCellType)
    extends UByteArrayTile(array, cols, rows)
       with UserDefinedByteNoDataConversions {
  val userDefinedByteNoDataValue = cellType.noDataValue
  def apply(i: Int): Int = udb2i(array(i))
  def update(i: Int, z: Int) { array(i) = i2udb(z) }
}

object UByteArrayTile {
  def apply(arr: Array[Byte], cols: Int, rows: Int): UByteArrayTile =
    new UByteConstantNoDataArrayTile(arr, cols, rows)

  def apply(arr: Array[Byte], cols: Int, rows: Int, cellType: UByteCells with NoDataHandling): UByteArrayTile =
    cellType match {
      case UByteCellType =>
        new UByteRawArrayTile(arr, cols, rows)
      case UByteConstantNoDataCellType =>
        new UByteConstantNoDataArrayTile(arr, cols, rows)
      case udct @ UByteUserDefinedNoDataCellType(_) =>
        new UByteUserDefinedNoDataArrayTile(arr, cols, rows, udct)
    }

  def ofDim(cols: Int, rows: Int): UByteArrayTile =
    new UByteConstantNoDataArrayTile(Array.ofDim[Byte](cols * rows), cols, rows)

  def empty(cols: Int, rows: Int): UByteArrayTile =
    new UByteConstantNoDataArrayTile(Array.ofDim[Byte](cols * rows), cols, rows)

  def fill(v: Byte, cols: Int, rows: Int): UByteArrayTile =
    new UByteConstantNoDataArrayTile(Array.ofDim[Byte](cols * rows).fill(v), cols, rows)

  def fromBytes(bytes: Array[Byte], cols: Int, rows: Int): UByteArrayTile =
    fromBytes(bytes, cols, rows, UByteConstantNoDataCellType)

  def fromBytes(bytes: Array[Byte], cols: Int, rows: Int, cellType: UByteCells with NoDataHandling): UByteArrayTile =
    cellType match {
      case UByteCellType =>
        new UByteRawArrayTile(bytes.clone, cols, rows)
      case UByteConstantNoDataCellType =>
        new UByteConstantNoDataArrayTile(bytes.clone, cols, rows)
      case udct @ UByteUserDefinedNoDataCellType(_) =>
        new UByteUserDefinedNoDataArrayTile(bytes.clone, cols, rows, udct)
    }

  def fromBytes(bytes: Array[Byte], cols: Int, rows: Int, replaceNoData: Byte): UByteArrayTile = {
    if(isNoData(replaceNoData))
      fromBytes(bytes, cols, rows)
    else {
      val arr = bytes.clone
      cfor(0)(_ < arr.size, _ + 1) { i =>
        val v = bytes(i)
        if(v == replaceNoData)
          arr(i) = byteNODATA
        else
          arr(i) = v
      }
      UByteArrayTile(arr, cols, rows)
    }
  }
}
