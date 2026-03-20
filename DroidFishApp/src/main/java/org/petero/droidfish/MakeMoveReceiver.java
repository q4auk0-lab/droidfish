package org.petero.droidfish;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives "org.petero.droidfish.MAKE_MOVE" broadcast from ChessAssist.
 * Forwards to DroidFish Activity via onNewIntent (singleTop).
 *
 * Exported=true gerekli: başka uygulama (ChessAssist) gönderiyor.
 * Android 11+ explicit broadcast (setPackage) ile geliyor.
 */
public class MakeMoveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String move = intent.getStringExtra("move");
        if (move == null || move.length() < 4) return;

        // Activity singleTop olduğu için onNewIntent() tetiklenir
        Intent fwd = new Intent(context, DroidFish.class);
        fwd.setAction("org.petero.droidfish.MAKE_MOVE_FWD");
        fwd.putExtra("move", move);
        fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(fwd);
    }
}
