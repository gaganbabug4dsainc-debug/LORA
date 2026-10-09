package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Online AI ko data bhejne se pehle permission dialog (Activity ke liye). Background thread se bulao. */
object Consent {
    /** 0 = 30 min, 1 = is session, 2 = mana. */
    fun ask(act: Activity, msg: String): Int {
        val latch = CountDownLatch(1)
        val res = AtomicInteger(2)
        act.runOnUiThread {
            if (act.isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            AlertDialog.Builder(act)
                .setTitle("Online AI ko data bhejna?")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Ab allow (30 min)") { _, _ -> res.set(0); latch.countDown() }
                .setNeutralButton("Is session") { _, _ -> res.set(1); latch.countDown() }
                .setNegativeButton("Mana") { _, _ -> res.set(2); latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return res.get()
    }
}
