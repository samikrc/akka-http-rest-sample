package com.ilabs.dsi.utils

import java.security.MessageDigest

import scala.collection.mutable
import scala.util.Random

/**
  * Created by samik on 26/3/17.
  */
object Utils
{
    def toHex(bytes: Array[Byte]): String = bytes.map(0xFF & _).map("%02x".format(_)).mkString("")
    def md5hash(string: String): String = toHex(MessageDigest.getInstance("MD5").digest(string.getBytes("UTF-8")))
    def constructRandomKey(length: Int = 30): String = md5hash(Random.nextString(length)).substring(0, length)

    /**
      * Method to construct the JSON response from a tuple and a schema.
      * @param tuple
      * @param keyNames
      */
    def constructJSONResponse(tuple: Product, keyNames: Array[String]) =
    {
        // Construct map
        val map = Map() ++ keyNames.iterator
            .zip(tuple.productIterator)
            .foldLeft(new mutable.HashMap[String, Any]())((accum, elem) => accum += tupleElementToMap(elem._1, elem._2))

        Json.Value(map).write
    }

    /**
      * Method to construct the JSON response from a vector of tuple (typically returned from database query) object and a schema.
      * @param tuples
      * @param keyNames
      */
    def constructJSONResponse(tuples: Vector[Product], keyNames: Array[String]) =
    {
        val arrayOfMaps = tuples.map(tuple =>
        {
            Map() ++ keyNames.iterator
            .zip(tuple.productIterator)
            .foldLeft(new mutable.HashMap[String, Any]())((accum, elem) => accum += tupleElementToMap(elem._1, elem._2))
        }).toArray
        Json.Value(arrayOfMaps).write
    }

    private def tupleElementToMap(key: String, a: Any): (String, Any) =
    {
        // Check if this element is JSON
        if(key.endsWith("/JSON"))
            (key.substring(0, key.indexOf("/JSON")) -> Json.parse(a.toString))
        else (key -> a)
    }
}
