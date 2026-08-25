/* SPDX-License-Identifier: Apache-2.0 */
package be.mygod.vpnhotspot.room

class AppDatabase private constructor() {
    companion object {
        const val DB_NAME = "vpnshare.db"
        val instance by lazy { AppDatabase() }
    }

    val clientRecordDao = ClientRecord.Dao()
    val trafficRecordDao = TrafficRecord.Dao()
}
