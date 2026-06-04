package com.suraksha.ai.model;

public class EmergencyContact {

    public String name;

    public String phone;

    public String trackingId;

    public EmergencyContact(
            String name,
            String phone,
            String trackingId
    ) {

        this.name =
                name;

        this.phone =
                phone;

        this.trackingId =
                trackingId;
    }
}