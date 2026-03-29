plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_upscaler")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
