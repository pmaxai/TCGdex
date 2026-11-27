import UIKit

enum Confidence { case sure, likely, unsure }

/// Another artwork the scanner considered. image: thumbnail of it (this version or another one with a picture).
struct Candidate: Identifiable, Equatable {
    let group: String
    let info: CardInfo
    let score: Float
    let reading: String
    var image: String? = nil
    var id: String { group + "|" + info.id + "|" + info.lang }
}

/// The printing a card starts with: Normal whenever the card has one, else the first it has.
func defaultPrinting(_ card: CardInfo) -> Printing.Kind? {
    card.printings.first { $0.kind == .normal }?.kind ?? card.printings.first?.kind
}

/// The card in the result panel (as the Android app's Shown).
struct Shown {
    let group: String
    /// the scanned version: its set, number and language
    var version: CardInfo
    /// English name and set, when the scanned version is not English
    var english: CardInfo?
    /// whose prices are shown: the version itself, or its English version if it has none
    var priceCard: CardInfo
    var printing: Printing.Kind?
    var printingByUser = false
    var confidence: Confidence
    var alternatives: [Candidate]
    /// the same card in other languages, to pick from
    var languages: [CardInfo]
    var text: CardText?
    var locked: Bool
    var fromPhoto: Bool
    /// grows with every new card
    var serial: Int
    /// the user picked this card or language: the printed text must not change it
    var versionByUser = false
    /// every version of this artwork (sets, languages), the shown one included, for switching
    var versions: [CardInfo] = []
    /// the card as the camera saw it, straightened: shown when there is no image (or no internet)
    var scan: UIImage?
    /// looked up in the card search, not seen by the camera
    var fromSearch = false
    /// price history of priceCard (nil: none in the data pack)
    var history: PriceHistory?

    /// Whose picture is shown: this version, else the same card in another language, else any version of the artwork.
    var imageCard: CardInfo? {
        let ordered = versions.filter { $0.id == version.id } + versions.filter { $0.id != version.id }
        return ([version, priceCard] + [english].compactMap { $0 } + ordered).first { $0.thumbnailUrl != nil }
    }

    /// The card is printed in an Asian language whose print isn't in the card data (yet): its code.
    var printedOnly: String? { text?.lang.flatMap { CardResolver.asian.contains($0) && $0 != version.lang ? $0 : nil } }
    var versionIndex: Int { versions.firstIndex { $0.id == version.id && $0.lang == version.lang } ?? -1 }
    var selected: Printing? { priceCard.printing(printing) }
    var value: Double? { selected?.value ?? priceCard.bestValue }
    /// the most valuable other printing, if it is worth more than the selected one
    var richerPrinting: Printing? {
        priceCard.printings.filter { $0.kind != selected?.kind && ($0.value ?? 0) > (value ?? 0) }.max { ($0.value ?? 0) < ($1.value ?? 0) }
    }
    var pricedFromEnglish: Bool { priceCard.id != version.id || priceCard.lang != version.lang }
    /// the language of the picture shown (TCGdex's are per language; the second source's are English)
    var pictureLang: String? { imageCard.map { $0.imageUrl != nil ? $0.lang : "en" } }
    /// "Symbolic picture – English print": the picture shown isn't this card as printed (an Asian print says so already)
    var symbolicPictureNote: String? {
        guard printedOnly == nil, let l = pictureLang, l != version.lang else { return nil }
        return "Symbolic picture – \(ShopTerms.englishName(l)) print"
    }
    /// PSA prices of the selected printing (eBay sales are kept apart per printing)
    var graded: Graded? { priceCard.gradedFor(selected?.kind) }
    var population: Population? { priceCard.populationFor(selected?.kind) }
    /// the card has PSA sales, just not for the selected printing
    var gradedOnlyOtherPrintings: Bool { graded == nil && !priceCard.graded.isEmpty }
}
