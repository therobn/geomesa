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

package geomesa.core.iterators

import java.text.DecimalFormat

import com.google.common.collect.HashBasedTable
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Envelope
import geomesa.core.data.{AccumuloDataStore, AccumuloFeatureStore}
import geomesa.core.index.{IndexSchemaBuilder, QueryHints}
import geomesa.utils.geotools.Conversions.RichSimpleFeature
import geomesa.utils.geotools.GridSnap
import org.geotools.data._
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.filter.text.ecql.ECQL
import org.geotools.filter.visitor.ExtractBoundsFilterVisitor
import org.geotools.geometry.jts.ReferencedEnvelope
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.junit.runner.RunWith
import org.opengis.feature.simple.SimpleFeature
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class LiveDensityIteratorTest extends Specification with Logging {

  sequential

  /**
   * WARNING: this test runs against a live accumulo instance
   */

  val params = Map(
    "instanceId"        -> "mycloud",
    "zookeepers"        -> "zoo1,zoo2,zoo3",
    "user"              -> "myuser",
    "password"          -> "mypass",
    "auths"             -> "user,admin",
    "visibilities"      -> "",
    "tableName"         -> "mytable",
    "indexSchemaFormat" -> new IndexSchemaBuilder("~").randomNumber(3).constant("TEST").geoHash(0, 3).date("yyyyMMdd").nextPart().geoHash(3, 2).nextPart().id().build(),
    "featureEncoding"   -> "avro")

  val sftName = "fr"

  val bbox = new { val lowx = -78.57; val lowy = 40.96; val highx = -77.23; val highy = 42.29 }

  val dates = "'2013-01-06T00:00:00.000Z' and '2013-01-09T00:00:00.000Z'"

  val size = new { val width = 300; val height = 150 }

  var snap: GridSnap = null

  val map = HashBasedTable.create[Double, Double, Long]()

  def getDataStore: AccumuloDataStore = {
    DataStoreFinder.getDataStore(params).asInstanceOf[AccumuloDataStore]
  }

  def printFeatures(featureIterator: SimpleFeatureIterator): Unit = {
    val features = new Iterator[SimpleFeature] {
      def hasNext = {
        val next = featureIterator.hasNext
        if (!next)
          featureIterator.close
        next
      }

      def next = {
        featureIterator.next
      }
    }.toList

    logger.debug("dates: {}", dates)

    logger.debug(s"total points: ${features.size}")

    logger.debug(s"unique points: ${features.groupBy(_.getDefaultGeometry).size}")

    val weights = features.map(_.getProperty("weight").getValue.toString.toDouble)

    logger.debug(s"total weight: ${weights.sum}")
    logger.debug(s"max weight: ${weights.max}")

    features.foreach {
      f =>
        val point = f.point
        map.put(point.getY, point.getX, map.get(point.getY, point.getX) + f.getProperty("weight").getValue.toString.toDouble.toLong)
    }

    logger.debug(s"max joined weight: ${map.values().max}")

    val output = new StringBuilder()

    val df = new DecimalFormat("0")

    map.rowMap().foreach {
      case (rowIdx, cols) =>
        cols.foreach {
          case (colIdx, v) =>
            if (v == 0) {
              output.append(" ")
            } else {
              output.append(df.format(v))
            }
        }
        output.append("\n")
    }

    logger.trace(output.toString())
  }

  def getQuery(query: String, width: Int, height: Int): Query = {
    val q = new Query(sftName, ECQL.toFilter(query))
    val geom = q.getFilter.accept(ExtractBoundsFilterVisitor.BOUNDS_VISITOR, null).asInstanceOf[Envelope]
    val env = new ReferencedEnvelope(geom, DefaultGeographicCRS.WGS84)
    q.getHints.put(QueryHints.DENSITY_KEY, java.lang.Boolean.TRUE)
    q.getHints.put(QueryHints.BBOX_KEY, env)
    q.getHints.put(QueryHints.WIDTH_KEY, width)
    q.getHints.put(QueryHints.HEIGHT_KEY, height)

    // re-create the snap and populate each point
    snap = new GridSnap(env, width, height)

    var i = 0
    while(i < width) {
      var j = 0
      while(j < height) {
        map.put(snap.y(j), snap.x(i), 0)
        j = j + 1
      }
      i = i + 1
    }

    q
  }

  "AccumuloDataStore" should {

    "connect to accumulo" in {

      skipped("Meant for integration testing")

      val ds = getDataStore

      val query = getQuery(s"(dtg between $dates) and BBOX(geom, ${bbox.lowx}, ${bbox.lowy}, ${bbox.highx}, ${bbox.highy})", size.width, size.height)

      // get the feature store used to query the GeoMesa data
      val featureStore = ds.getFeatureSource(sftName).asInstanceOf[AccumuloFeatureStore]

      // execute the query
      val results = featureStore.getFeatures(query)

      // loop through all results
      printFeatures(results.features)

      success
    }
  }

}
