package geotrellis.spark.io.s3

import java.io.ByteArrayInputStream
import com.amazonaws.services.s3.model._
import java.util.concurrent.ConcurrentHashMap
import com.amazonaws.services.s3.internal.AmazonS3ExceptionBuilder
import scala.collection.immutable.TreeMap
import com.typesafe.scalalogging.slf4j._
import org.apache.commons.io.IOUtils
import scala.collection.JavaConverters._

class MockS3Client() extends S3Client with LazyLogging {
  import MockS3Client._

  def listObjects(r: ListObjectsRequest): ObjectListing = this.synchronized {
    if (null == r.getMaxKeys)
      r.setMaxKeys(64)

    val ol = new ObjectListing
    ol.setBucketName(r.getBucketName)
    ol.setPrefix(r.getPrefix)
    ol.setDelimiter(r.getDelimiter)
    ol.setMaxKeys(r.getMaxKeys)
    val listing = ol.getObjectSummaries


    val bucket = getBucket(r.getBucketName)
    var marker = r.getMarker

    if (null == marker) {
      bucket.findFirstKey(r.getPrefix) match {
        case Some(key) => marker = key
        case None => return ol
      }
      logger.debug(s"MOVING MARKER prefix=${r.getPrefix} marker=${marker}")
    }

    var keyCount = 0
    var nextMarker: String = null
    val iter = bucket.entries.from(marker).iterator
    logger.debug(s"LISTING prefix=${r.getPrefix}, marker=$marker")

    var endSeen = false
    while (iter.hasNext && keyCount <= r.getMaxKeys) {
      val (key, bytes) = iter.next
      if (key startsWith r.getPrefix) {
        if (keyCount < r.getMaxKeys){
          logger.debug(s" + ${key}")
          val os = new S3ObjectSummary
          os.setBucketName(bucket.name)
          os.setKey(key)
          os.setSize(bytes.length)
          listing.add(os)
        }
        if (keyCount == r.getMaxKeys) {
          nextMarker = key
        }
        keyCount += 1
      }else{
        endSeen = true
      }
    }

    ol.setNextMarker(nextMarker)
    ol.setTruncated(null != nextMarker)
    ol
  }

  def getObject(r: GetObjectRequest): S3Object =  this.synchronized {
    val bucket = getBucket(r.getBucketName)
    val key = r.getKey
    logger.debug(s"GET ${r.getKey}")
    bucket.synchronized {
      if (bucket.contains(key)) {
        val obj = new S3Object()
        val bytes = bucket(key)
        val md = new ObjectMetadata()
        md.setContentLength(bytes.length)
        obj.setKey(key)
        obj.setBucketName(r.getBucketName)
        obj.setObjectContent(new ByteArrayInputStream(bytes))
        obj.setObjectMetadata(md)
        obj
      } else {
        val ex = new AmazonS3ExceptionBuilder()
        ex.setErrorCode("NoSuchKey")
        ex.setErrorMessage(s"The specified key does not exist: $key")
        ex.setStatusCode(404)
        throw ex.build
      }
    }
  }

  def listKeys(listObjectsRequest: ListObjectsRequest): Seq[String] = ???

  def readBytes(getObjectRequest: GetObjectRequest): Array[Byte] = {
    val obj = getObject(getObjectRequest)
    val inStream = obj.getObjectContent
    try {
      IOUtils.toByteArray(inStream)
    } finally {
      inStream.close()
    }
  }

  def putObject(r: PutObjectRequest): PutObjectResult = this.synchronized {
    logger.debug(s"PUT ${r.getKey}")
    val bucket = getBucket(r.getBucketName)
    bucket.synchronized {
      bucket.put(r.getKey, IOUtils.toByteArray(r.getInputStream))
    }
    new PutObjectResult()
  }

  def deleteObject(r: DeleteObjectRequest): Unit = this.synchronized {
    logger.debug(s"DELETE ${r.getKey}")
    val bucket = getBucket(r.getBucketName)
    bucket.synchronized {
      bucket.remove(r.getKey)
    }
  }

  def listNextBatchOfObjects(r: ObjectListing): ObjectListing = this.synchronized {
    if(!r.isTruncated) r
    else {
      val ol = new ObjectListing
      ol.setBucketName(r.getBucketName)
      ol.setPrefix(r.getPrefix)
      ol.setDelimiter(r.getDelimiter)
      ol.setMaxKeys(r.getMaxKeys)
      val listing = ol.getObjectSummaries

      val bucket = getBucket(r.getBucketName)
      val marker = r.getNextMarker

      var keyCount = 0
      var nextMarker: String = null
      val iter = bucket.entries.from(marker).iterator
      logger.debug(s"LISTING prefix=${r.getPrefix}, marker=$marker")

      var endSeen = false
      while (iter.hasNext && keyCount <= r.getMaxKeys) {
        val (key, bytes) = iter.next
        if (key startsWith r.getPrefix) {
          if (keyCount < r.getMaxKeys) {
            logger.debug(s" + ${key}")
            val os = new S3ObjectSummary
            os.setBucketName(bucket.name)
            os.setKey(key)
            os.setSize(bytes.length)
            listing.add(os)
          }
          if (keyCount == r.getMaxKeys) {
            nextMarker = key
          }
          keyCount += 1
        } else {
          endSeen = true
        }
      }

      ol.setNextMarker(nextMarker)
      ol.setTruncated(null != nextMarker)
      ol
    }
  }

  def deleteObjects(r: DeleteObjectsRequest): Unit = {
    val keys = r.getKeys
    logger.debug(s"DELETE LIST ${keys}")

    val bucket = getBucket(r.getBucketName)
    bucket.synchronized {
      bucket.remove(keys.asScala.map(_.getKey))
    }
  }

  def copyObject(r: CopyObjectRequest): CopyObjectResult = this.synchronized {
    logger.debug(s"COPY ${r.getSourceKey}")

    val destBucket = getBucket(r.getDestinationBucketName)
    destBucket.synchronized {
      val obj = getObject(r.getSourceBucketName, r.getSourceKey)
      putObject(r.getDestinationBucketName, r.getDestinationKey, obj.getObjectContent, obj.getObjectMetadata)
    }
    new CopyObjectResult()
  }

  def setRegion(region: com.amazonaws.regions.Region): Unit = {}
}

object MockS3Client{
  class Bucket(val name: String) {
    var entries = new TreeMap[String, Array[Byte]]

    def apply(key: String): Array[Byte] =
      entries.get(key).get

    def put(key: String, bytes: Array[Byte]) =
      entries += key -> bytes

    def contains(key: String): Boolean =
      entries.contains(key)

    def remove(key: String) =
      entries -= key

    def remove(keys: Seq[String]) =
      entries --= keys

    /** return first key that matches this prefix */
    def findFirstKey(prefix: String): Option[String] = {
      entries
        .find { case (key, bytes) => key startsWith prefix }
        .map { _._1 }
    }
  }

  def reset(): Unit =
    buckets.clear()

  val buckets = new ConcurrentHashMap[String, Bucket]()

  def getBucket(name: String): Bucket = {
    if (buckets.containsKey(name)) {
      buckets.get(name)
    }else{
      val bucket = new Bucket(name)
      buckets.put(name, bucket)
      bucket
    }
  }
}
