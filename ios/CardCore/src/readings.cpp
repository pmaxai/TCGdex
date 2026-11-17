// Readings: the image geometry shared with the pipeline (port of Readings.kt).
#include "cardcore.hpp"

#include <opencv2/imgproc.hpp>
#include <algorithm>

namespace cardcore {
namespace readings {

static std::vector<cv::Point2f> f(const std::vector<cv::Point2d>& v) {
    std::vector<cv::Point2f> o;
    for (auto& p : v) o.emplace_back(static_cast<float>(p.x), static_cast<float>(p.y));
    return o;
}

cv::Mat rectify(const cv::Mat& rgb, const Quad& quad, int w, int h) {
    auto src = f({quad[0], quad[1], quad[2], quad[3]});
    auto dst = f({{0.0, 0.0}, {w - 1.0, 0.0}, {w - 1.0, h - 1.0}, {0.0, h - 1.0}});
    cv::Mat m = cv::getPerspectiveTransform(src, dst);
    cv::Mat out;
    cv::warpPerspective(rgb, out, m, cv::Size(w, h), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    return out;
}

cv::Mat toCard(const cv::Mat& rgb, const Quad& quad, const Geometry& g) {
    cv::Mat r = rectify(rgb, quad, g.rectW, g.rectH);
    cv::Mat card;
    cv::resize(r, card, cv::Size(g.cardW, g.cardH), 0, 0, cv::INTER_AREA);
    return card;
}

cv::Mat rot180(const cv::Mat& m) {
    cv::Mat o;
    cv::rotate(m, o, cv::ROTATE_180);
    return o;
}

static std::vector<cv::Point2f> artDst(const Geometry& g) {
    double cw = g.cardW, ch = g.cardH;
    double x0 = g.artBox[0], y0 = g.artBox[1], x1 = g.artBox[2], y1 = g.artBox[3];
    return f({{x0 * cw, y0 * ch}, {x1 * cw - 1, y0 * ch}, {x1 * cw - 1, y1 * ch - 1}, {x0 * cw, y1 * ch - 1}});
}

std::pair<cv::Mat, cv::Mat> asArtwork(const cv::Mat& rgb, const Quad& quad, const Geometry& g) {
    Quad q = CardDetector::orderQuad(quad);
    Quad land{q[3], q[0], q[1], q[2]};                    // np.roll(q, 1): long side horizontal
    double cw = g.cardW, ch = g.cardH;
    auto dst = artDst(g);
    int rx = static_cast<int>(g.artBox[0] * cw);
    int ry = static_cast<int>(g.artBox[1] * ch);
    cv::Rect roi(rx, ry, static_cast<int>(g.artBox[2] * cw) - rx, static_cast<int>(g.artBox[3] * ch) - ry);
    std::vector<cv::Mat> out;
    for (const Quad& src : {land, Quad{land[2], land[3], land[0], land[1]}}) {
        cv::Mat m = cv::getPerspectiveTransform(f({src[0], src[1], src[2], src[3]}), dst);
        cv::Mat warped;
        cv::warpPerspective(rgb, warped, m, cv::Size(g.cardW, g.cardH), cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                            cv::Scalar(128, 128, 128));
        cv::Mat canvas(g.cardH, g.cardW, CV_8UC3, cv::Scalar(128, 128, 128));
        warped(roi).copyTo(canvas(roi));
        out.push_back(canvas);
    }
    return {out[0], out[1]};
}

Quad cardQuadFromArtwork(const Quad& quad, bool upsideDown, const Geometry& g, double margin) {
    Quad q = CardDetector::orderQuad(quad);
    Quad land{q[3], q[0], q[1], q[2]};
    Quad src = upsideDown ? Quad{land[2], land[3], land[0], land[1]} : land;
    double cw = g.cardW, ch = g.cardH;
    cv::Mat h = cv::getPerspectiveTransform(artDst(g), f({src[0], src[1], src[2], src[3]}));   // card -> photo
    double m = margin;
    std::vector<cv::Point2f> corners = f({{-m * cw, -m * ch}, {(1 + m) * cw, -m * ch}, {(1 + m) * cw, (1 + m) * ch}, {-m * cw, (1 + m) * ch}});
    std::vector<cv::Point2f> out;
    cv::perspectiveTransform(corners, out, h);
    return Quad{cv::Point2d(out[0]), cv::Point2d(out[1]), cv::Point2d(out[2]), cv::Point2d(out[3])};
}

cv::Mat cardForReading(const cv::Mat& rgb, const Quad& quad, bool upsideDown) {
    cv::Mat r = rectify(rgb, quad, 720, 1006);
    return upsideDown ? rot180(r) : r;
}

cv::Mat artInput(const cv::Mat& card, const Geometry& g) {
    cv::Mat sub = card(cv::Rect(g.artX, g.artY, g.artW, g.artH));
    cv::Mat out;
    cv::resize(sub, out, cv::Size(g.input, g.input), 0, 0, cv::INTER_AREA);
    return out;
}

Quad guideQuad(int w, int h, const Prior& prior) {
    double ch = prior.h * h;
    double cw = ch * 63 / 88;
    if (cw > 0.9 * w) { cw = 0.9 * w; ch = cw * 88 / 63; }
    double x0 = prior.cx * w - cw / 2;
    double y0 = prior.cy * h - ch / 2;
    return Quad{cv::Point2d(x0, y0), cv::Point2d(x0 + cw, y0), cv::Point2d(x0 + cw, y0 + ch), cv::Point2d(x0, y0 + ch)};
}

Prior guidePrior(int w, int h, double size) {
    double s = std::clamp(size, 0.3, 0.95);
    return Prior{0.5, 0.5, std::min(s, std::min(s + 0.1, 0.9) * w / (CardDetector::CARD_ASPECT * h))};
}

}  // namespace readings
}  // namespace cardcore
