# StoreKit test configuration

`OverpricedCardboard.storekit` holds stand-in products for testing purchases without the App Store:
in the simulator tests (`Tests/StoreTests.swift`, with StoreKitTest) and, in Xcode, by choosing it
in the scheme (Run › Options › StoreKit Configuration).

The product IDs and prices are placeholders. Replace them with the real ones once they exist in
App Store Connect (the shop enters the same IDs in its admin, so `/store/offers` names them).
