package com.crealytics.spark.excel

import java.io.File

import org.scalacheck.{Arbitrary, Gen, Shrink}
import Arbitrary.{arbLong => _, arbString => _, _}
import org.scalacheck.ScalacheckShapeless._
import org.scalatest.FunSuite
import org.scalatest.prop.PropertyChecks
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types._

object IntegrationSuite {

  case class ExampleData(
    aBoolean: Boolean,
    aByte: Byte,
    aShort: Short,
    anInt: Int,
    aLong: Long,
    aDouble: Double,
    aString: String,
    aDate: java.sql.Date
  )

  val exampleDataSchema = ScalaReflection.schemaFor[ExampleData].dataType.asInstanceOf[StructType]

  implicit val arbitraryDateFourDigits = Arbitrary[java.sql.Date](
    Gen
      .chooseNum[Long](0L, (new java.util.Date).getTime + 1000000)
      .map(new java.sql.Date(_))
  )

  // Unfortunately we're losing some precision when parsing Longs
  // due to the fact that we have to read them as Doubles and then cast.
  // We're restricting our tests to Int-sized Longs in order not to fail
  // because of this issue.
  implicit val arbitraryLongWithLosslessDoubleConvertability: Arbitrary[Long] =
    Arbitrary[Long] { arbitrary[Int].map(_.toLong) }

  implicit val arbitraryStringWithoutUnicodeCharacters: Arbitrary[String] =
    Arbitrary[String](Gen.alphaNumStr)

  val rowGen: Gen[ExampleData] = arbitrary[ExampleData]
  val rowsGen: Gen[List[ExampleData]] = Gen.listOf(rowGen)
}

class IntegrationSuite extends FunSuite with PropertyChecks with DataFrameSuiteBase {
  import IntegrationSuite._

  import spark.implicits._

  implicit def shrinkOnlyNumberOfRows[A]: Shrink[List[A]] = Shrink.shrinkContainer[List, A]

  val PackageName = "com.crealytics.spark.excel"
  val sheetName = "test sheet"


  test("parses known datatypes correctly") {
    forAll(rowsGen, MinSuccessful(20)) { rows =>
      val expected = spark.createDataset(rows).toDF

      val tempFile: File = File.createTempFile("spark_excel_integration_test_", ".xlsx")
      val fileName = tempFile.getAbsolutePath
      expected.write
        .format(PackageName)
        .option("sheetName", sheetName)
        .option("useHeader", "true")
        .mode("overwrite")
        .save(fileName)

      val result = spark.read.format(PackageName)
        .option("sheetName", sheetName)
        .option("useHeader", "true")
        .option("treatEmptyValuesAsNulls", "true")
        .option("inferSchema", "false")
        .option("addColorColumns", "false")
        .schema(exampleDataSchema)
        .load(fileName)

      assertDataFrameEquals(expected, result)
    }
  }
}
