package com.nuvio.app.core.device

import platform.UIKit.UIDevice
import com.nuvio.app.core.network.PrivateBackend

/**
 * iOS counterpart to Android's `DeviceCapabilityRegistrar.registerAsync()`, which populates
 * [PrivateBackend.deviceProfileId] there. iOS has no device-capability registration flow today,
 * so this just seeds a stable per-install id — `identifierForVendor` resets on full
 * vendor-uninstall (weaker than Android's id) but requires no extra entitlement and errs toward
 * false-positives, not false-negatives, for the backend's abuse heuristic. Good enough for v1.
 */
object DeviceIdentity {
    fun initIos() {
        PrivateBackend.deviceProfileId = UIDevice.currentDevice.identifierForVendor?.UUIDString
    }
}
