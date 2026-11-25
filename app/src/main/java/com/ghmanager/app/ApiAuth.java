package com.ghmanager.app;

import android.content.Context;

final class ApiAuth {
    private volatile String token;
    private final Context ctx;
    private final String accId;

    ApiAuth(String token) {
        this.token = token;
        this.ctx = null;
        this.accId = "";
    }

    ApiAuth(Context c) {
        this.ctx = c.getApplicationContext();
        Accounts.Acc a = Accounts.active(this.ctx);
        this.accId = a == null ? "" : a.id;
        this.token = a == null ? "" : Accounts.tokenOf(a);
    }

    String accId() {
        return accId;
    }

    String bearer() {
        if (ctx != null && !accId.isEmpty() && Accounts.needsRefresh(ctx, accId)) {
            String t = Accounts.refresh(ctx, accId, token);
            if (!t.isEmpty()) token = t;
        }
        return token;
    }

    boolean renew() {
        if (ctx == null || accId.isEmpty()) return false;
        String t = Accounts.refresh(ctx, accId, token);
        if (t.isEmpty() || t.equals(token)) return false;
        token = t;
        return true;
    }
}
