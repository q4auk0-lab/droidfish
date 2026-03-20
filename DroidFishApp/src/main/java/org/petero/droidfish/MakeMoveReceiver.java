package org.petero.droidfish;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ChessAssist'ten gelen broadcast'leri alır:
 *
 * 1. org.petero.droidfish.SET_FEN
 *    extra: "fen" (String) — pozisyonu set et, motor hesaplar
 *
 * 2. org.petero.droidfish.MAKE_MOVE  (eski, hala destekleniyor)
 *    extra: "move" (String UCI) — hamle yap
 */
public class MakeMoveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Intent fwd = new Intent(context, DroidFish.class);
        fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        switch (action) {
            case "org.petero.droidfish.SET_FEN":
                String fen = intent.getStringExtra("fen");
                if (fen == null || fen.isEmpty()) return;
                fwd.setAction("org.petero.droidfish.SET_FEN_FWD");
                fwd.putExtra("fen", fen);
                break;

            case "org.petero.droidfish.MAKE_MOVE":
                String move = intent.getStringExtra("move");
                if (move == null || move.length() < 4) return;
                fwd.setAction("org.petero.droidfish.MAKE_MOVE_FWD");
                fwd.putExtra("move", move);
                break;

            default:
                return;
        }

        context.startActivity(fwd);
    }
}
