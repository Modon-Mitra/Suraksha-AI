package com.suraksha.ai.utils;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class FirebaseTest {

    public static void test() {

        FirebaseFirestore db =
                FirebaseFirestore.getInstance();

        Map<String, Object> data =
                new HashMap<>();

        data.put("status", "working");

        data.put(
                "time",
                System.currentTimeMillis()
        );

        db.collection("test")
                .document("connection")
                .set(data)

                .addOnSuccessListener(unused -> {

                    Log.d(
                            "FirebaseTest",
                            "SUCCESS"
                    );
                })

                .addOnFailureListener(e -> {

                    Log.e(
                            "FirebaseTest",
                            "FAILED",
                            e
                    );
                });
    }
}