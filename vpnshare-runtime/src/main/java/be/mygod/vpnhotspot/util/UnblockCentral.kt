/* SPDX-License-Identifier: Apache-2.0 */
package be.mygod.vpnhotspot.util

/** Only the root-process initialization hooks are needed by the embedded routing core. */
object UnblockCentral {
    var needInit: Boolean = false
    val openPidFd: Unit get() = Unit
}
