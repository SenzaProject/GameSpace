package com.senzaproject.gamespace;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

final class Store {
    private Store() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gamespace", Context.MODE_PRIVATE);
    }

    static Set<String> games(Context c) {
        return new HashSet<>(sp(c).getStringSet("games", new HashSet<String>()));
    }

    static void setGames(Context c, Set<String> g) {
        sp(c).edit().putStringSet("games", new HashSet<>(g)).apply();
    }
}
