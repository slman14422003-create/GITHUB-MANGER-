package com.ghmanager.app;

import java.io.IOException;

    final class FmCancel extends IOException {
        FmCancel() {
            super("cancelled");
        }
    }
