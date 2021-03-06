/*
 *
 *  * Copyright 2014 Commonwealth Computer Research, Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the License);
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an AS IS BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package geomesa.core.integration.data

import java.io.{FileWriter, PrintWriter}
import java.text.SimpleDateFormat
import java.util.Date

import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Coordinate
import geomesa.core.data.{AccumuloDataStore, AccumuloFeatureStore}
import geomesa.utils.geotools.Conversions._
import org.geotools.data._
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.filter.text.cql2.CQL
import org.geotools.geometry.jts.JTSFactoryFinder

import scala.collection.JavaConversions._
import scala.util.Try

object DataExporter extends App with Logging {

  // replace this with your load specification
  val load: LoadAttributes = null

  val params = Map("instanceId"    -> "mycloud",
                    "zookeepers"   -> "zoo1,zoo2,zoo3",
                    "user"         -> "user",
                    "password"     -> "password",
                    "auths"        -> "",
                    "visibilities" -> "",
                    "tableName"    -> load.table)

  val extractor = new DataExporter(load, params)
  val features = extractor.queryFeatures()
  extractor.writeFeatures(features)
}

class DataExporter(load: LoadAttributes, params: Map[_,_]) extends Logging {

  lazy val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  lazy val geometryFactory = JTSFactoryFinder.getGeometryFactory

  /**
   * Writes features to a tmp file in tsv format
   *
   * @param features
   */
  def writeFeatures(features: SimpleFeatureIterator): Unit = {

    val attributeTypes = List(load.idAttribute) ++ load.attributes.split(",")

    val attributes = attributeTypes.map(_.split(":")(0))

    val fr = new PrintWriter(new FileWriter(s"/tmp/${load.name}.tsv"))

    // header
    fr.println(attributeTypes.mkString("\t"))

    var count = 0

    features.foreach { sf =>
      val map = scala.collection.mutable.Map.empty[String, Object]

      // copy attributes into map where we can manipulate them
      attributes.foreach(a => Try(map.put(a, sf.getAttribute(a))))

      // check that ID is set in the map
      val id = map.getOrElse(attributes(0), null)
      if (id == null || id.toString.isEmpty) {
        map.put(attributes(0), sf.getID)
      }

      // calculate geom and dtg
      load.latitudeAttribute match {
        case None =>
        case Some(attr) =>
          val lat = sf.getAttribute(load.latitudeAttribute.get).toString.toDouble
          val lon = sf.getAttribute(load.longitudeAttribute.get).toString.toDouble
          val geom = geometryFactory.createPoint(new Coordinate(lon, lat))
          map.put("*geom", geom)
      }
      load.dateAttribute match {
        case None =>
        case Some(attr) =>
          val date = sf.getAttribute(attr)
          if (date.isInstanceOf[Date]) {
            map.put("dtg", date)
          } else {
            map.put("dtg", dateFormat.parse(date.toString))
          }
      }

      // put the values into a checked list
      val attributeValues = attributes.map { a =>
        val value = map.getOrElse(a, null)
        if (value == null) {
          ""
        } else if (value.isInstanceOf[java.util.Date]) {
          dateFormat.format(value.asInstanceOf[java.util.Date])
        } else {
          value.toString
        }
      }

      val tabSeparatedString = attributeValues.mkString("\t")

      fr.println(tabSeparatedString)

      fr.flush()
      count = count + 1

      if (count % 10000 == 0) {
        logger.debug("wrote {} features", "" + count)
      }
    }
    fr.close()

    logger.info(s"wrote $count features to '/tmp/${load.name}.tsv'")
  }

  /**
   *
   * @return
   */
  def queryFeatures(): SimpleFeatureIterator = {

    logger.debug("querying")

    val ds = DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]

    val query = new Query(load.name, CQL.toFilter(load.query))

    // get the feature store used to query the GeoMesa data
    val featureStore = ds.getFeatureSource(load.name).asInstanceOf[AccumuloFeatureStore]

    // execute the query
    featureStore.getFeatures(query).features()
  }
}

case class LoadAttributes(name: String,
                          table: String,
                          attributes: String,
                          idAttribute: String,
                          latitudeAttribute: Option[String],
                          longitudeAttribute: Option[String],
                          dateAttribute: Option[String],
                          query: String)
