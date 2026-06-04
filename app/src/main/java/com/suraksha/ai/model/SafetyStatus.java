package com.suraksha.ai.model;

/**
 * SafetyStatus – represents the current safety state of the user.
 */
public enum SafetyStatus {
    SAFE,        // green  – all sensors normal
    WARNING,     // yellow – suspicious reading
    DANGER,      // red    – confirmed threat
    SOS_ACTIVE   // red+   – SOS triggered
}
