/* SPDX-License-Identifier: Apache-2.0 */
package be.mygod.vpnhotspot.room

import android.net.MacAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

data class ClientRecord(
    val mac: MacAddress,
    var nickname: CharSequence = "",
    var blocked: Boolean = false,
    var macLookupPending: Boolean = true,
) {
    class Dao {
        private val records = LinkedHashMap<MacAddress, ClientRecord>()
        private val blocked = MutableStateFlow<List<MacAddress>>(emptyList())

        fun lookupOrDefaultBlocking(mac: MacAddress) = synchronized(records) { records[mac] ?: ClientRecord(mac) }
        suspend fun lookupOrDefault(mac: MacAddress) = lookupOrDefaultBlocking(mac)
        fun lookupOrDefaultFlow(mac: MacAddress): Flow<ClientRecord> = blocked.asStateFlow().map {
            lookupOrDefaultBlocking(mac)
        }
        suspend fun update(value: ClientRecord) {
            synchronized(records) {
                records[value.mac] = value
                blocked.value = records.values.filter(ClientRecord::blocked).map(ClientRecord::mac)
            }
        }
        suspend fun upsert(mac: MacAddress, operation: suspend ClientRecord.() -> Unit) {
            lookupOrDefaultBlocking(mac).also { operation(it); update(it) }
        }
        fun observeBlockedMacs(): Flow<List<MacAddress>> = blocked.asStateFlow()
    }
}
