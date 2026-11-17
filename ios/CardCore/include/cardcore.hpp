// CardCore: the card scanner's recognition (detector, readings, fingerprint search) in C++.
// A line-by-line port of the Android app's vision package (CardDetector.kt, Readings.kt,
// Matcher.kt, Recognizer.kt), which itself ports the pipeline (pipeline/tcgscan/detect.py,
// embedders.py, evaluate.py). The iPhone app calls it through a small Objective-C++ bridge;
// the same sources build on Linux for the parity test against the pipeline (test/).
#pragma once

#include <opencv2/core.hpp>
#include <array>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace cardcore {

using Quad = std::array<cv::Point2d, 4>;

/** A found card: corners tl, tr, br, bl (portrait order) in image pixels. */
struct Detection { Quad quad; double score; };

/** Where the guide frame expects the card: centre and card height as fractions of the image. */
struct Prior { double cx, cy, h; };

/**
 * Image geometry shared with the pipeline (the data pack's meta table): a straightened card is
 * warped to rectW x rectH, stored at cardW x cardH, and the fingerprint model sees the artwork
 * rectangle (artX, artY, artW, artH) resized to input x input.
 */
struct Geometry {
    int rectW, rectH, cardW, cardH, artX, artY, artW, artH;
    /** artwork box as fractions of the card (x0, y0, x1, y1) */
    std::array<double, 4> artBox;
    int input;
};

/** Finds cards in a photo (pipeline/tcgscan/detect.py, as CardDetector.kt). */
class CardDetector {
public:
    static constexpr double CARD_ASPECT = 63.0 / 88.0;
    static constexpr bool ALT_LINES = true;
    static constexpr double ALT_MAX_IOU = 0.85;

    /** rgb: CV_8UC3 in RGB order */
    std::vector<Detection> detect(const cv::Mat& rgb, int maxCards = 1, double minAreaFrac = 0.03, const Prior* prior = nullptr);
    /** the detector's answer first, then up to k-1 clearly different guesses (detect_hypotheses) */
    std::vector<Detection> hypotheses(const cv::Mat& rgb, int k = 2, double minAreaFrac = 0.03, const Prior* prior = nullptr);

    static Quad orderQuad(const Quad& p);
    static double quadIou(const Quad& a, const Quad& b);
    static double polyArea(const std::vector<cv::Point2d>& q);
    static double polyArea(const Quad& q);

private:
    struct Impl;
};

/** Image readings shared with the pipeline (Readings.kt). */
namespace readings {
    cv::Mat rectify(const cv::Mat& rgb, const Quad& quad, int w, int h);
    cv::Mat toCard(const cv::Mat& rgb, const Quad& quad, const Geometry& g);
    cv::Mat rot180(const cv::Mat& m);
    std::pair<cv::Mat, cv::Mat> asArtwork(const cv::Mat& rgb, const Quad& quad, const Geometry& g);
    Quad cardQuadFromArtwork(const Quad& quad, bool upsideDown, const Geometry& g, double margin = 0.06);
    cv::Mat cardForReading(const cv::Mat& rgb, const Quad& quad, bool upsideDown);
    cv::Mat artInput(const cv::Mat& card, const Geometry& g);
    Quad guideQuad(int w, int h, const Prior& prior);
    Prior guidePrior(int w, int h, double size = 0.7);
}

/** One candidate artwork: its group, best reference entry and which reading matched it. */
struct Hit { int group; float score; int entry; int reading; };

/**
 * Nearest neighbours over every reference fingerprint (Matcher.kt): a rough score from the
 * first coarseDim dimensions for every reference, then exact scores for the best shortlist.
 * Groups are numbered (the pack's artwork group of each entry, as an index).
 */
class Matcher {
public:
    Matcher(const float* index, int n, int dim, std::vector<int> groupOf, int coarseDim = 128, int shortlist = 300);
    std::vector<Hit> search(const std::vector<std::vector<float>>& readings, int k = 5, int pool = 40) const;
    /** entries never to return (empty: none) */
    std::vector<bool> excluded;
    static std::vector<int> topIndices(const std::vector<float>& v, int m, const std::vector<int>* among = nullptr);
private:
    float score(int i, const std::vector<std::vector<float>>& readings, int d, int* which) const;
    const float* index_;
    int n_, dim_, coarseDim_, shortlist_;
    std::vector<int> groupOf_;
};

/** The fingerprint model: (N, size, size, 3) float RGB 0..255 in, (N, dim) unit vectors out. */
class Fingerprinter {
public:
    virtual ~Fingerprinter() = default;
    virtual std::vector<std::vector<float>> embed(const std::vector<cv::Mat>& images) = 0;
};

/** What the scanner saw in one photo or frame. */
struct Recognition {
    /** card outline used (detected, or the guide frame); valid only when hasQuad */
    Quad quad{};
    bool hasQuad = false;
    bool detected = false;
    std::vector<Hit> hits;
    /** score gap between the best and the second card: the confidence */
    float margin = 0;
    /** taken by hand: the likely cards over every outline read (else = hits) */
    std::vector<Hit> candidates;
    std::map<std::string, double> timingsMs;
};

class Recognizer {
public:
    Recognizer(Geometry g, std::shared_ptr<Fingerprinter> fp, std::shared_ptr<Matcher> matcher, float sureMargin);
    Recognition recognise(const cv::Mat& rgb, const Prior* prior = nullptr, bool manual = false);
    /** the upright card's outline for this reading (margin: room around a card rebuilt from its artwork box) */
    bool cardQuad(const Recognition& r, const Hit& hit, double margin, Quad& out) const;
    const Geometry& geometry() const { return g_; }
    static constexpr int HYPOTHESES = 2;
    static constexpr int MANUAL_CANDIDATES = 10;
private:
    struct Read { Quad quad; std::vector<Hit> hits; float margin; };
    Read read(const cv::Mat& rgb, const Quad& quad, int k, int pool, std::map<std::string, double>& t);
    Geometry g_;
    std::shared_ptr<Fingerprinter> fp_;
    std::shared_ptr<Matcher> matcher_;
    CardDetector detector_;
    float sureMargin_;
};

}  // namespace cardcore
