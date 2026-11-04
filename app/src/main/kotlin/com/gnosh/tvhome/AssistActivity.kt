package com.gnosh.tvhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * mic-to-claude: the assist and voice command entry (ACTION_ASSIST, ACTION_VOICE_COMMAND; also what the voice session
 * starts). It shows nothing: it opens Ask Claude, listening, in the main task ([AgendaActivity.ACTION_ASK]) and
 * finishes. Like ACTION_ASK, any app may send it; it only opens the Ask view, and carries no extras over.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(this, AgendaActivity::class.java)
                    .setAction(AgendaActivity.ACTION_ASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
