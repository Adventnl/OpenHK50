package org.openhk50.band

import android.os.Handler
import org.openhk50.data.SleepNight
import java.time.LocalDate

internal class DetailTransfer(
    private val main: Handler,
    private val link: GattLink,
    private val today: LocalDate,
    private val days: Int,
    private val currentDay: () -> LocalDate,
    private val sleep: (List<SleepNight>) -> Unit,
    private val oxygen: (LocalDate, IntArray) -> Unit,
    private val done: (Boolean, String?) -> Unit
) {
    private val decoder = DetailProtocol.Decoder()
    private var active = true
    private var deadline: Runnable? = null
    private var responseType = -1
    private var response: ((ByteArray) -> Boolean)? = null
    private var gotSleep = false
    private val oxygenDays = mutableSetOf<Int>()

    fun start(username: String?) {
        val failure = "Couldn’t start sleep and oxygen sync. Reconnect and try again."
        after(10_000) { finish(failure) }
        link.prepareDetails { ready ->
            if (!active) return@prepareDetails
            clearWait()
            if (!ready) {
                finish(failure)
            } else if (username != null) {
                sendSetup(DetailProtocol.login(username)) {
                    sendSetup(DetailProtocol.packet(DetailProtocol.INITIALIZE)) { readSleep() }
                }
            } else {
                readSleep()
            }
        }
    }

    private fun sendSetup(packet: ByteArray, next: () -> Unit) {
        link.writeDetail(packet) { sent ->
            if (!active) return@writeDetail
            if (sent) after(400, next)
            else finish("Couldn’t start sleep and oxygen sync. Reconnect and try again.")
        }
    }

    private fun readSleep() {
        val day = if (days > 1) 0xff else 0
        request(DetailProtocol.SLEEP, byteArrayOf(day.toByte(), 1), { body ->
            val nights = DetailProtocol.sleep(body, today)
            if (nights != null) {
                gotSleep = true
                if (nights.isNotEmpty()) sleep(nights)
            }
            nights != null
        }) { readOxygen(0) }
    }

    private fun readOxygen(day: Int) {
        if (day >= days) {
            finish(when {
                gotSleep && oxygenDays.size == days -> null
                gotSleep -> "Some blood oxygen history didn’t reply."
                oxygenDays.isNotEmpty() -> "Sleep history didn’t reply."
                else -> "No sleep or blood oxygen history replied. Sync again."
            })
            return
        }
        if (day in oxygenDays) {
            readOxygen(day + 1)
            return
        }
        request(DetailProtocol.OXYGEN, byteArrayOf((-day).toByte()), { body ->
            val records = DetailProtocol.oxygen(body, days)
            for ((offset, intervals) in records) {
                if (oxygenDays.add(offset)) oxygen(today.minusDays(offset.toLong()), intervals)
            }
            day in records
        }) { readOxygen(day + 1) }
    }

    private fun request(type: Int, body: ByteArray, accept: (ByteArray) -> Boolean, next: () -> Unit) {
        responseType = type
        // A notification can arrive while Android is still reporting the write.
        var acknowledged = false
        var received = false
        response = {
            if (received || !accept(it)) false else {
                received = true
                if (acknowledged) advance(next)
                true
            }
        }
        link.writeDetail(DetailProtocol.packet(type, body)) { sent ->
            if (!active) return@writeDetail
            acknowledged = sent
            if (!sent) finish("Couldn’t sync sleep and oxygen. Reconnect and try again.")
            else if (received) advance(next)
            else after(3_500) { advance(next) }
        }
    }

    fun frame(bytes: ByteArray) {
        if (!active) return
        if (today != currentDay()) {
            finish("The day changed. Sync again.")
            return
        }
        for (frame in decoder.add(bytes)) {
            if (frame.type == responseType && response?.invoke(frame.body) == true) break
        }
    }

    private fun advance(next: () -> Unit) {
        clearWait()
        if (today == currentDay()) after(0, next) else finish("The day changed. Sync again.")
    }

    private fun after(delay: Long, run: () -> Unit) {
        deadline = Runnable(run).also { main.postDelayed(it, delay) }
    }

    private fun clearWait() {
        deadline?.let(main::removeCallbacks)
        deadline = null
        response = null
        responseType = -1
    }

    fun cancel() {
        active = false
        clearWait()
    }

    private fun finish(message: String?) {
        cancel()
        done(gotSleep || oxygenDays.isNotEmpty(), message)
    }
}
