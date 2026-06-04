package com.suraksha.ai.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import com.suraksha.ai.model.EmergencyContact;

import java.util.ArrayList;
import java.util.List;

public class SmsHelper {

    private static final String TAG = "SmsHelper";

    // =========================
    // Send SOS SMS silently
    // to ALL contacts
    // =========================

    public static void sendSosMessages(
            Context ctx,
            double lat,
            double lon
    ) {

        List<EmergencyContact> contacts =
                new PrefsManager(ctx).getContacts();

        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts to SMS");
            return;
        }

        String mapLink =
                "https://maps.google.com/?q="
                        + lat
                        + ","
                        + lon;

        String message =
                "\uD83D\uDEA8 SURAKSHA AI SOS ALERT \uD83D\uDEA8\n\n"
                        + "I may be in danger. Please help immediately!\n\n"
                        + "\uD83D\uDCCD Live Location:\n"
                        + mapLink;

        SmsManager smsManager = SmsManager.getDefault();

        for (EmergencyContact contact : contacts) {

            try {

                // Split into parts if message > 160 chars
                ArrayList<String> parts =
                        smsManager.divideMessage(message);

                if (parts.size() == 1) {

                    smsManager.sendTextMessage(
                            contact.phone,
                            null,
                            message,
                            null,
                            null
                    );

                } else {

                    smsManager.sendMultipartTextMessage(
                            contact.phone,
                            null,
                            parts,
                            null,
                            null
                    );
                }

                Log.d(TAG, "SOS SMS sent to " + contact.name);

            } catch (Exception e) {

                Log.e(TAG, "Failed to send SMS to " + contact.name, e);
            }
        }
    }

    // =========================
    // Auto Call a Contact
    // =========================

    public static void callContact(
            Context ctx,
            String phone
    ) {

        try {

            Intent callIntent =
                    new Intent(Intent.ACTION_CALL);

            callIntent.setData(
                    Uri.parse(
                            "tel:" + phone
                    )
            );

            callIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
            );

            ctx.startActivity(callIntent);

        } catch (Exception e) {

            e.printStackTrace();
        }
    }
}