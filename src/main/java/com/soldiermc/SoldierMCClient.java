package com.soldiermc;

import com.soldiermc.client.SoldierClient;
import com.soldiermc.client.SoldierDiagnostics;
import com.soldiermc.client.VoiceMenuHud;
import net.fabricmc.api.ClientModInitializer;

public class SoldierMCClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SoldierClient.init();
        SoldierDiagnostics.init();
        VoiceMenuHud.init();
    }
}
