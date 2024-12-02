package de.uniwuerzburg.omod.io.sqlite

import de.uniwuerzburg.omod.core.models.Activity
import de.uniwuerzburg.omod.io.json.OutputActivity
import de.uniwuerzburg.omod.io.json.OutputEntry
import de.uniwuerzburg.omod.io.json.OutputLeg
import de.uniwuerzburg.omod.io.json.OutputTrip
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTWriter
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types.INTEGER


fun writeSQLite(output: List<OutputEntry>, file: File) : Boolean {
    val url = "jdbc:sqlite:${file.absolutePath}"
    val geometryFactory = GeometryFactory()
    val wktWriter = WKTWriter()

    try {
        val conn = DriverManager.getConnection(url)
        conn.autoCommit = false

        // Person table
        conn.createStatement().execute("DROP TABLE IF EXISTS person;")
        val personTableSQL = (
            "CREATE TABLE person (" +
            "	id INTEGER PRIMARY KEY," +
            "	homogenousGroup text NOT NULL," +
            "	mobilityGroup text NOT NULL," +
            "	age INTEGER," +
            "	sex text NOT NULL," +
            "	carAccess INT NOT NULL" +
            ");"
        )
        conn.createStatement().execute(personTableSQL)

        // Day table
        conn.createStatement().execute("DROP TABLE IF EXISTS day;")
        val dayTableSQL = (
            "CREATE TABLE day (" +
            "	id INTEGER PRIMARY KEY," +
            "	dayType text NOT NULL" +
            ");"
        )
        conn.createStatement().execute(dayTableSQL)

        // Person table
        conn.createStatement().execute("DROP TABLE IF EXISTS plan;")
        val planTableSQL = (
            "CREATE TABLE plan (" +
            "	id INTEGER PRIMARY KEY," +
            "	person INTEGER NOT NULL," +
            "	day INTEGER NOT NULL," +
            "	type text NOT NULL," +
            "	legID INTEGER NOT NULL," +
            "	activityType text," +
            "	startTime text," +
            "	stayTimeMinute REAL," +
            "	lat REAL," +
            "	lon REAL," +
            "	dummyLoc Int," +
            "	inFocusArea Int," +
            "	mode text," +
            "	distanceKilometer REAL," +
            "	timeMinute REAL," +
            "   route text," +
            "	FOREIGN KEY(person) REFERENCES person(id)," +
            "	FOREIGN KEY(day) REFERENCES day(id)" +
            ");"
        )
        conn.createStatement().execute(planTableSQL)

        // Prepare statements
        val personPStmt = conn.prepareStatement(
        "INSERT INTO person" +
            "(id, homogenousGroup, mobilityGroup, age, sex, carAccess)" +
            " VALUES(?,?,?,?,?,?)"
        )
        val dayPStmt = conn.prepareStatement(
        "INSERT INTO day" +
            "(id,dayType)" +
            " VALUES(?,?)"
        )
        val knownDayIds = mutableSetOf<Int>()
        val legPStmt = conn.prepareStatement(
            "INSERT INTO plan" +
                 "(person, day, type, legID, activityType, startTime, stayTimeMinute, lat, lon," +
                  "dummyLoc, inFocusArea, mode, distanceKilometer, timeMinute, route)" +
                 " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )

        for ((i, entry) in output.withIndex()) {
            // Fill person table
            personPStmt.setInt(1, entry.id)
            personPStmt.setString(2, entry.homogenousGroup.toString())
            personPStmt.setString(3, entry.mobilityGroup.toString())
            if (entry.age == null) {
                personPStmt.setNull(4, INTEGER)
            } else {
                personPStmt.setInt(4, entry.age)
            }
            personPStmt.setString(5, entry.sex.toString())
            personPStmt.setBoolean(6, entry.carAccess)
            personPStmt.executeUpdate()

            for (day in entry.mobilityDemand) {
                if (!knownDayIds.contains(day.day)) {
                    knownDayIds.add(day.day)

                    // Fill day table
                    dayPStmt.setInt(1, day.day)
                    dayPStmt.setString(2, day.dayType.toString())
                    dayPStmt.executeUpdate()
                }

                for (leg in day.plan) {
                    // Fill plan table
                    legPStmt.setInt(1, entry.id)
                    legPStmt.setInt(2, day.day)
                    legPStmt.setInt(4, leg.legID)
                    if (leg is OutputActivity) {
                        legPStmt.setString(3, "Activity")
                        legPStmt.setString(5, leg.activityType.toString())
                        legPStmt.setString(6, leg.startTime)
                        if (leg.stayTimeMinute != null) {
                            legPStmt.setDouble(7, leg.stayTimeMinute)
                        } else {
                            legPStmt.setNull(7,  java.sql.Types.DOUBLE)
                        }
                        legPStmt.setDouble(8, leg.lat)
                        legPStmt.setDouble(9, leg.lon)
                        legPStmt.setBoolean(10, leg.dummyLoc)
                        legPStmt.setBoolean(11, leg.inFocusArea)
                        legPStmt.setNull(12, java.sql.Types.VARCHAR)
                        legPStmt.setNull(13, java.sql.Types.DOUBLE)
                        legPStmt.setNull(14, java.sql.Types.DOUBLE)
                        legPStmt.setNull(15, java.sql.Types.VARCHAR)
                    } else if (leg is OutputTrip) {
                        legPStmt.setString(3, "Trip")
                        legPStmt.setNull(5, java.sql.Types.VARCHAR)
                        legPStmt.setString(6, leg.startTime)
                        legPStmt.setNull(7,  java.sql.Types.DOUBLE)
                        legPStmt.setNull(8, java.sql.Types.DOUBLE)
                        legPStmt.setNull(9, java.sql.Types.DOUBLE)
                        legPStmt.setNull(10, java.sql.Types.BOOLEAN)
                        legPStmt.setNull(11,java.sql.Types.BOOLEAN)
                        legPStmt.setString(12, leg.mode.toString())
                        if (leg.distanceKilometer != null) {
                            legPStmt.setDouble(13, leg.distanceKilometer)
                        } else {
                            legPStmt.setNull(13, java.sql.Types.DOUBLE)
                        }
                        if (leg.timeMinute != null) {
                            legPStmt.setDouble(14, leg.timeMinute)
                        } else {
                            legPStmt.setNull(14, java.sql.Types.DOUBLE)
                        }

                        if ((leg.lats != null) && (leg.lons != null)) {
                            val coords = leg.lats.zip(leg.lons)
                                .map { (lat, lon) -> Coordinate(lat, lon) }
                                .toTypedArray()
                            val line = geometryFactory.createLineString(coords)
                            val wkt = wktWriter.write(line)
                            legPStmt.setString(15, wkt)
                        } else {
                            legPStmt.setNull(15, java.sql.Types.VARCHAR)
                        }
                    }
                    legPStmt.executeUpdate()
                }
            }

            // Write batch
            if (i % 1000 == 0) {
                conn.commit()
            }
        }
        conn.commit()
        conn.close()
    } catch (e: SQLException) {
        println(e.message)
        return false
    }
    return true
}