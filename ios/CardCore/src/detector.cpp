// CardDetector: a line-by-line port of CardDetector.kt (pipeline/tcgscan/detect.py).
// Kept deliberately close to the Kotlin, so the two stay easy to compare.
#include "cardcore.hpp"

#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <numeric>
#include <optional>

namespace cardcore {
namespace {

using P = cv::Point2d;

constexpr double PI = 3.14159265358979323846;

double dist(const P& a, const P& b) { return std::hypot(a.x - b.x, a.y - b.y); }

P centroid(const std::vector<P>& q) {
    double x = 0, y = 0;
    for (auto& p : q) { x += p.x; y += p.y; }
    return {x / q.size(), y / q.size()};
}
P centroid(const Quad& q) { return centroid(std::vector<P>(q.begin(), q.end())); }

/** Python's round(): halves go to the even neighbour. */
long pyRound(double x) { return static_cast<long>(std::nearbyint(x)); }

std::vector<uint8_t> bytesOf(const cv::Mat& m) {
    cv::Mat c = m.isContinuous() ? m : m.clone();
    return std::vector<uint8_t>(c.data, c.data + c.total() * c.channels());
}

double medianOfHist(const int* hist, long n) {
    auto kth = [&](long k) {
        long acc = 0;
        for (int v = 0; v <= 255; v++) { acc += hist[v]; if (acc > k) return v; }
        return 255;
    };
    return n % 2 == 1 ? static_cast<double>(kth(n / 2)) : (kth(n / 2 - 1) + kth(n / 2)) / 2.0;
}

cv::Mat autoCanny(const cv::Mat& ch, double loF = 0.66, double hiF = 1.33) {
    int hist[256] = {0};
    cv::Mat c = ch.isContinuous() ? ch : ch.clone();
    const uint8_t* d = c.data;
    long n = static_cast<long>(c.total());
    for (long i = 0; i < n; i++) hist[d[i]]++;
    double med = medianOfHist(hist, n);
    int lo = static_cast<int>(std::max(5.0, loF * med));
    int hi = static_cast<int>(std::min(255.0, std::max(hiF * med, static_cast<double>(lo + 25))));
    cv::Mat out;
    cv::Canny(ch, out, lo, hi);
    return out;
}

std::optional<P> lineIntersection(const P& p1, const P& p2, const P& p3, const P& p4) {
    double d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x);
    if (std::abs(d) < 1e-9) return std::nullopt;
    double a = p1.x * p2.y - p1.y * p2.x;
    double b = p3.x * p4.y - p3.y * p4.x;
    return P((a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d, (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d);
}

/** Corners from four side lines: corner i = side i-1 x side i. */
std::optional<Quad> intersectAll(const std::vector<std::pair<P, P>>& lines) {
    Quad out;
    for (int i = 0; i < 4; i++) {
        auto& l1 = lines[(i + 3) % 4];
        auto& l2 = lines[i];
        auto p = lineIntersection(l1.first, l1.second, l2.first, l2.second);
        if (!p) return std::nullopt;
        out[i] = *p;
    }
    return out;
}

std::vector<cv::Point2f> toF(const Quad& q) {
    std::vector<cv::Point2f> v;
    for (auto& p : q) v.emplace_back(static_cast<float>(p.x), static_cast<float>(p.y));
    return v;
}

/** Java's MatOfPoint: coordinates cut to whole numbers (toward zero) */
std::vector<cv::Point> toIntTrunc(const std::vector<P>& q) {
    std::vector<cv::Point> v;
    for (auto& p : q) v.emplace_back(static_cast<int>(p.x), static_cast<int>(p.y));
    return v;
}

bool inside(const P& pt, const Quad& quad) {
    return cv::pointPolygonTest(toF(quad), cv::Point2f(static_cast<float>(pt.x), static_cast<float>(pt.y)), false) >= 0;
}

double quadScore(const Quad& quad, double imgArea) {
    Quad q = CardDetector::orderQuad(quad);
    double sides[4];
    for (int i = 0; i < 4; i++) sides[i] = dist(q[i], q[(i + 1) % 4]);
    if (*std::min_element(sides, sides + 4) < 10) return 0.0;
    double w = (sides[0] + sides[2]) / 2;
    double h = (sides[1] + sides[3]) / 2;
    double aspect = w / h;
    if (!(aspect > 0.5 && aspect < 0.95)) return 0.0;
    double frac = CardDetector::polyArea(q) / imgArea;
    if (frac > 0.98) return 0.0;
    double cosv[4];
    for (int i = 0; i < 4; i++) {
        const P& p = q[i];
        const P& a = q[(i + 3) % 4];
        const P& b = q[(i + 1) % 4];
        double v1x = a.x - p.x, v1y = a.y - p.y, v2x = b.x - p.x, v2y = b.y - p.y;
        cosv[i] = std::abs(v1x * v2x + v1y * v2y) / (std::hypot(v1x, v1y) * std::hypot(v2x, v2y) + 1e-6);
    }
    if (*std::max_element(cosv, cosv + 4) > 0.5) return 0.0;
    double t = (aspect - CardDetector::CARD_ASPECT) / 0.07;
    double aspectScore = std::exp(-(t * t));
    double angleScore = 1.0 - (cosv[0] + cosv[1] + cosv[2] + cosv[3]) / 4;
    return aspectScore * angleScore * std::sqrt(frac);
}

std::pair<double, double> edgeSupport(const Quad& quad, const uint8_t* edges, int w, int h, int n = 40) {
    Quad q = CardDetector::orderQuad(quad);
    double fr[4];
    for (int i = 0; i < 4; i++) {
        const P& a = q[i];
        const P& b = q[(i + 1) % 4];
        int hits = 0;
        for (int k = 0; k < n; k++) {
            double t = 0.12 + (0.88 - 0.12) * k / (n - 1);
            int px = static_cast<int>(std::rint(a.x + (b.x - a.x) * t));
            int py = static_cast<int>(std::rint(a.y + (b.y - a.y) * t));
            if (px >= 0 && px < w && py >= 0 && py < h && edges[static_cast<size_t>(py) * w + px] != 0) hits++;
        }
        fr[i] = static_cast<double>(hits) / n;
    }
    return {(fr[0] + fr[1] + fr[2] + fr[3]) / 4, *std::min_element(fr, fr + 4)};
}

double priorWeight(const Quad& quad, const Prior& prior, int hh, int ww) {
    Quad q = CardDetector::orderQuad(quad);
    P c = centroid(q);
    double size = (dist(q[3], q[0]) + dist(q[2], q[1])) / 2 / hh;
    double d = std::hypot(c.x / ww - prior.cx, c.y / hh - prior.cy);
    double a = d / 0.18;
    double g1 = std::exp(-(a * a));
    double b = std::log(std::max(size, 1e-3) / prior.h) / 0.35;
    double g2 = std::exp(-(b * b));
    return g1 * g2 + 0.05;
}

std::optional<Quad> hullToQuad(const std::vector<P>& hull) {
    std::vector<P> pts = hull;
    if (pts.size() < 4) return std::nullopt;
    while (pts.size() > 4) {
        int n = static_cast<int>(pts.size());
        double best = INFINITY;
        int bestI = -1;
        std::optional<P> bestP;
        for (int i = 0; i < n; i++) {
            const P& a = pts[(i - 1 + n) % n];
            const P& b = pts[i];
            const P& c = pts[(i + 1) % n];
            const P& d = pts[(i + 2) % n];
            auto p = lineIntersection(a, b, c, d);
            if (!p) continue;
            if ((p->x - b.x) * (b.x - a.x) + (p->y - b.y) * (b.y - a.y) < 0 ||
                (p->x - c.x) * (c.x - d.x) + (p->y - c.y) * (c.y - d.y) < 0) continue;
            double added = std::abs((c.x - b.x) * (p->y - b.y) - (p->x - b.x) * (c.y - b.y)) / 2;
            if (added < best) { best = added; bestI = i; bestP = p; }
        }
        if (!bestP) return std::nullopt;
        pts[bestI] = *bestP;
        pts.erase(pts.begin() + (bestI + 1) % n);
    }
    return Quad{pts[0], pts[1], pts[2], pts[3]};
}

struct EdgePoints { std::vector<float> xs, ys; };

EdgePoints rawEdgePoints(const cv::Mat& gray) {
    cv::Mat a = autoCanny(gray);
    cv::Mat b = autoCanny(gray, 0.33, 0.8);
    cv::bitwise_or(a, b, a);
    int w = a.cols;
    auto bytes = bytesOf(a);
    EdgePoints e;
    for (size_t i = 0; i < bytes.size(); i++)
        if (bytes[i] != 0) { e.xs.push_back(static_cast<float>(i % w)); e.ys.push_back(static_cast<float>(i / w)); }
    return e;
}

Quad refineQuad(const Quad& quad, const EdgePoints& e, double bandFrac = 0.015) {
    Quad q = CardDetector::orderQuad(quad);
    double cardH = dist(q[3], q[0]);
    double band = std::max(3.0, bandFrac * cardH);
    if (e.xs.size() < 50) return q;
    std::vector<std::pair<P, P>> lines;
    for (int i = 0; i < 4; i++) {
        const P& a = q[i];
        const P& b = q[(i + 1) % 4];
        double dx = b.x - a.x, dy = b.y - a.y;
        double len = std::hypot(dx, dy);
        double ux = dx / len, uy = dy / len;
        double nx = -uy, ny = ux;
        std::vector<cv::Point2f> sel;
        for (size_t k = 0; k < e.xs.size(); k++) {
            double rx = e.xs[k] - a.x;
            double ry = e.ys[k] - a.y;
            double t = rx * ux + ry * uy;
            double d = rx * nx + ry * ny;
            if (std::abs(d) < band && t > 0.1 * len && t < 0.9 * len) sel.emplace_back(e.xs[k], e.ys[k]);
        }
        if (sel.size() < 0.3 * 0.8 * len) return q;
        cv::Vec4f v;
        cv::fitLine(sel, v, cv::DIST_HUBER, 0, 0.01, 0.01);
        lines.emplace_back(P(v[2], v[3]), P(static_cast<double>(v[2] + v[0]), static_cast<double>(v[3] + v[1])));
    }
    auto out = intersectAll(lines);
    if (!out) return q;
    for (int i = 0; i < 4; i++)
        if (std::max(std::abs((*out)[i].x - q[i].x), std::abs((*out)[i].y - q[i].y)) > 0.05 * cardH) return q;
    return *out;
}

/** If the quad is the card's inner panel, grow it to the outer edge of the border. */
Quad expandToBorder(const Quad& quad, const std::vector<uint8_t>& edges, int w, int h, const cv::Mat& img,
                    double lo = 0.015, double hi = 0.065) {
    Quad q = CardDetector::orderQuad(quad);
    double cardH = dist(q[3], q[0]);
    P c = centroid(q);
    double start = lo * cardH, stop = hi * cardH;
    double step = std::max(1.0, 0.004 * cardH);
    int nOffs = static_cast<int>(std::ceil((stop - start) / step));
    if (nOffs < 3) return q;
    double ts[40];
    for (int i = 0; i < 40; i++) ts[i] = 0.15 + (0.85 - 0.15) * i / 39.0;
    std::vector<std::pair<P, P>> lines;
    std::vector<double> found;
    for (int i = 0; i < 4; i++) {
        const P& a = q[i];
        const P& b = q[(i + 1) % 4];
        double len = std::max(1e-6, dist(a, b));
        double ux = (b.x - a.x) / len, uy = (b.y - a.y) / len;
        double nx = -uy, ny = ux;
        if (((a.x + b.x) / 2 - c.x) * nx + ((a.y + b.y) / 2 - c.y) * ny < 0) { nx = -nx; ny = -ny; }
        double bestOff = -1.0, bestSup = 0.0;
        for (int o = 0; o < nOffs; o++) {
            double off = start + o * step;
            double pax = a.x + nx * off, pay = a.y + ny * off;
            double pbx = b.x + nx * off, pby = b.y + ny * off;
            int okN = 0, hits = 0;
            for (double t : ts) {
                int px = static_cast<int>(std::rint(pax + (pbx - pax) * t));
                int py = static_cast<int>(std::rint(pay + (pby - pay) * t));
                if (px >= 0 && px < w && py >= 0 && py < h) {
                    okN++;
                    if (edges[static_cast<size_t>(py) * w + px] != 0) hits++;
                }
            }
            if (okN < 0.9 * 40) break;
            double sup = static_cast<double>(hits) / okN;
            if (sup > bestSup + 0.05) { bestOff = off; bestSup = sup; }
            else if (bestSup >= 0.6 && sup < bestSup - 0.2) break;
        }
        if (bestOff < 0 || bestSup < 0.6) return q;
        found.push_back(bestOff);
        lines.emplace_back(P(a.x + nx * bestOff, a.y + ny * bestOff), P(b.x + nx * bestOff, b.y + ny * bestOff));
    }
    if (*std::max_element(found.begin(), found.end()) / *std::min_element(found.begin(), found.end()) > 1.7) return q;
    auto out = intersectAll(lines);
    if (!out) return q;
    // the band between old and new outline must be one colour (a card border)
    cv::Mat mask = cv::Mat::zeros(h, w, CV_8U);
    cv::fillConvexPoly(mask, toIntTrunc(std::vector<P>(out->begin(), out->end())), cv::Scalar(1.0));
    cv::fillConvexPoly(mask, toIntTrunc(std::vector<P>(q.begin(), q.end())), cv::Scalar(0.0));
    auto mb = bytesOf(mask);
    auto ib = bytesOf(img);
    std::vector<size_t> ring;
    for (size_t i = 0; i < mb.size(); i++) if (mb[i] != 0) ring.push_back(i);
    if (ring.size() < 50) return q;
    double med[3];
    for (int ch = 0; ch < 3; ch++) {
        int hist[256] = {0};
        for (size_t i : ring) hist[ib[i * 3 + ch]]++;
        med[ch] = medianOfHist(hist, static_cast<long>(ring.size()));
    }
    size_t close = 0;
    for (size_t i : ring) {
        double s = 0;
        for (int ch = 0; ch < 3; ch++) s += std::abs(ib[i * 3 + ch] - med[ch]);
        if (s < 60) close++;
    }
    if (static_cast<double>(close) / ring.size() < 0.6) return q;
    return *out;
}

std::vector<cv::Mat> edgeMaps(const cv::Mat& small, const cv::Mat& gray) {
    cv::Mat k3 = cv::Mat::ones(3, 3, CV_8U);
    std::vector<cv::Mat> out;
    cv::Mat a = autoCanny(gray); cv::dilate(a, a, k3); out.push_back(a);
    cv::Mat b = autoCanny(gray, 0.33, 0.8); cv::dilate(b, b, k3); out.push_back(b);
    // colour edges: a yellow card border on a beige table is clear in colour, not in grey
    cv::Mat lab;
    cv::cvtColor(small, lab, cv::COLOR_RGB2Lab);
    cv::GaussianBlur(lab, lab, cv::Size(5, 5), 0.0);
    std::vector<cv::Mat> ch;
    cv::split(lab, ch);
    cv::Mat e = autoCanny(ch[0]);
    for (int i = 1; i <= 2; i++) {
        cv::Mat c;
        cv::Canny(ch[i], c, 20.0, 50.0);
        cv::bitwise_or(e, c, e);
    }
    cv::morphologyEx(e, e, cv::MORPH_CLOSE, k3, cv::Point(-1, -1), 2);
    out.push_back(e);
    // adaptive threshold catches low-contrast borders
    cv::Mat th;
    cv::adaptiveThreshold(gray, th, 255.0, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY, 31, 5.0);
    cv::bitwise_not(th, th);
    cv::Mat k5 = cv::Mat::ones(5, 5, CV_8U);
    cv::morphologyEx(th, th, cv::MORPH_CLOSE, k5);
    out.push_back(th);
    return out;
}

/** detect._line_quads: card-shaped quads from straight edge lines (fingers or a sleeve break the contour). */
std::vector<Quad> lineQuads(const cv::Mat& edges, int maxLines = 18) {
    int h = edges.rows, w = edges.cols;
    double md = std::min(h, w);
    std::vector<cv::Vec4i> seg;
    cv::HoughLinesP(edges, seg, 1.0, PI / 180, 40, 0.12 * md, 0.02 * std::max(h, w));
    size_t nSeg = seg.size();
    if (nSeg == 0) return {};
    std::vector<double> phi(nSeg), rho(nSeg), length(nSeg);
    for (size_t i = 0; i < nSeg; i++) {
        double x1 = seg[i][0], y1 = seg[i][1], x2 = seg[i][2], y2 = seg[i][3];
        length[i] = std::hypot(x2 - x1, y2 - y1);
        double p = std::fmod(std::atan2(y2 - y1, x2 - x1) + PI / 2, PI);
        if (p < 0) p += PI;
        phi[i] = p;
        rho[i] = x1 * std::cos(p) + y1 * std::sin(p);
    }
    // merge collinear segments into lines, strongest first (stable order, like the pipeline)
    std::vector<size_t> order(nSeg);
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return length[a] > length[b]; });
    std::vector<std::array<double, 3>> lns;   // phi, rho, support
    for (size_t i : order) {
        bool merged = false;
        for (auto& ln : lns) {
            double dphi = std::abs(phi[i] - ln[0]);
            double r = rho[i];
            if (dphi > PI / 2) { dphi = PI - dphi; r = -r; }
            if (dphi < 3.0 * PI / 180 && std::abs(r - ln[1]) < 0.015 * md) { ln[2] += length[i]; merged = true; break; }
        }
        if (!merged) lns.push_back({phi[i], rho[i], length[i]});
    }
    std::stable_sort(lns.begin(), lns.end(), [](const auto& a, const auto& b) { return a[2] > b[2]; });
    if (static_cast<int>(lns.size()) > maxLines) lns.resize(maxLines);
    auto angdiff = [](double a, double b) { double d = std::fmod(std::abs(a - b), PI); return std::min(d, PI - d); };
    auto sameDirRho = [](const std::array<double, 3>& a, const std::array<double, 3>& b) {
        return std::abs(a[0] - b[0]) < PI / 2 ? b[1] : -b[1];
    };
    struct Pair2 { std::array<double, 3> a, b; double d; };
    std::vector<Pair2> pairs;
    for (size_t i = 0; i < lns.size(); i++)
        for (size_t j = i + 1; j < lns.size(); j++) {
            auto& a = lns[i];
            auto& b = lns[j];
            if (angdiff(a[0], b[0]) < 12.0 * PI / 180) {
                double d = std::abs(a[1] - sameDirRho(a, b));
                if (d > 0.12 * md) pairs.push_back({a, b, d});
            }
        }
    std::vector<Quad> out;
    for (size_t p = 0; p < pairs.size(); p++)
        for (size_t q = p + 1; q < pairs.size(); q++) {
            auto& a = pairs[p].a; auto& b = pairs[p].b; double d1 = pairs[p].d;
            auto& c = pairs[q].a; auto& e = pairs[q].b; double d2 = pairs[q].d;
            double ad = angdiff(a[0], c[0]);
            if (!(ad > 65.0 * PI / 180 && ad < 115.0 * PI / 180)) continue;
            double ratio = std::min(d1, d2) / std::max(d1, d2);
            if (!(ratio > 0.6 && ratio < 0.85)) continue;
            std::vector<P> pts;
            const std::array<double, 3>* combos[4][2] = {{&a, &c}, {&c, &b}, {&b, &e}, {&e, &a}};
            for (auto& cb : combos) {
                auto& l1 = *cb[0];
                auto& l2 = *cb[1];
                double m00 = std::cos(l1[0]), m01 = std::sin(l1[0]);
                double m10 = std::cos(l2[0]), m11 = std::sin(l2[0]);
                double det = m00 * m11 - m01 * m10;
                if (std::abs(det) < 1e-6) break;
                pts.emplace_back((l1[1] * m11 - m01 * l2[1]) / det, (m00 * l2[1] - l1[1] * m10) / det);
            }
            if (pts.size() < 4) continue;
            bool outside = false;
            for (auto& pt : pts) if (pt.x < -0.02 * w || pt.x > 1.02 * w || pt.y < -0.02 * h || pt.y > 1.02 * h) outside = true;
            if (outside) continue;
            Quad raw;
            for (int i = 0; i < 4; i++) raw[i] = P(static_cast<float>(pts[i].x), static_cast<float>(pts[i].y));
            Quad oq = CardDetector::orderQuad(Quad{pts[0], pts[1], pts[2], pts[3]});
            std::vector<P> oqf;
            for (auto& pt : oq) oqf.emplace_back(static_cast<float>(pt.x), static_cast<float>(pt.y));
            if (cv::isContourConvex(toIntTrunc(oqf))) out.push_back(raw);
        }
    return out;
}

struct Cand { double score; Quad quad; double support; int si; bool fromLines; };
struct Kept { Quad quad; double score; int si; };

struct Ctx {
    double scale;
    cv::Mat small, gray, unionMap;
    std::vector<uint8_t> unionBytes;
    std::optional<EdgePoints> rawEdges;
};

}  // namespace

struct CardDetector::Impl {
    std::vector<int> workSizes{300, 450, 600};
    double supportMin = 0.15;
    int supportPow = 2;
    bool refine = true, expand = true, useHull = true;

    Ctx scaleCandidates(const cv::Mat& img, int workSize, double minAreaFrac, const Prior* prior, int si,
                        std::vector<Cand>& out, bool lines) {
        double scale = std::min(1.0, static_cast<double>(workSize) / std::min(img.rows, img.cols));
        cv::Mat small;
        if (scale < 1.0) cv::resize(img, small, cv::Size(), scale, scale, cv::INTER_AREA); else small = img;
        cv::Mat gray;
        cv::cvtColor(small, gray, cv::COLOR_RGB2GRAY);
        cv::GaussianBlur(gray, gray, cv::Size(5, 5), 0.0);
        double imgArea = static_cast<double>(small.rows) * small.cols;
        auto maps = edgeMaps(small, gray);
        cv::Mat unionMap = cv::Mat::zeros(gray.size(), CV_8U);
        for (auto& m : maps) cv::bitwise_or(unionMap, m, unionMap);
        int k = std::max(3, static_cast<int>(pyRound(0.004 * std::max(small.rows, small.cols))) | 1);
        cv::dilate(unionMap, unionMap, cv::Mat::ones(k, k, CV_8U));
        Ctx x{scale, small, gray, unionMap, bytesOf(unionMap), std::nullopt};
        const uint8_t* ub = x.unionBytes.data();
        int uw = unionMap.cols, uh = unionMap.rows;

        auto consider = [&](const Quad& quad, bool fromLines) {
            double s = quadScore(quad, imgArea);
            if (s <= 0) return;
            auto [supMean, supMin] = edgeSupport(quad, ub, uw, uh);
            if (supMin < supportMin) return;
            if (prior) s *= priorWeight(quad, *prior, uh, uw);
            double pw = 1.0;
            for (int i = 0; i < supportPow; i++) pw *= supMean;
            Quad full;
            for (int i = 0; i < 4; i++) full[i] = P(quad[i].x / scale, quad[i].y / scale);
            out.push_back({s * pw, full, supMean, si, fromLines});
        };

        for (auto& edges : maps) {
            std::vector<std::vector<cv::Point>> contours;
            cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
            for (auto& c : contours) {
                double area = cv::contourArea(c);
                if (area < minAreaFrac * imgArea) continue;
                std::vector<cv::Point2f> c2f(c.begin(), c.end());
                double peri = cv::arcLength(c2f, true);
                std::vector<Quad> quads;
                for (double eps : {0.015, 0.025, 0.04}) {
                    std::vector<cv::Point2f> approx;
                    cv::approxPolyDP(c2f, approx, eps * peri, true);
                    if (approx.size() == 4) {
                        std::vector<P> ap(approx.begin(), approx.end());
                        if (cv::isContourConvex(toIntTrunc(ap))) {
                            quads.push_back(Quad{ap[0], ap[1], ap[2], ap[3]});
                            break;
                        }
                    }
                }
                if (useHull) {
                    std::vector<int> idx;
                    cv::convexHull(c, idx);
                    std::vector<cv::Point> hullPts;
                    for (int i : idx) hullPts.push_back(c[i]);
                    double hullArea = cv::contourArea(hullPts);
                    if (hullArea > 0 && area / hullArea > 0.8) {
                        std::vector<cv::Point2f> h2f(hullPts.begin(), hullPts.end());
                        std::vector<cv::Point2f> ap;
                        cv::approxPolyDP(h2f, ap, 0.005 * peri, true);
                        auto hq = hullToQuad(std::vector<P>(ap.begin(), ap.end()));
                        if (hq && area / std::max(CardDetector::polyArea(*hq), 1.0) > 0.8) quads.push_back(*hq);
                    }
                }
                for (auto& q : quads) consider(q, false);
            }
        }
        if (lines)
            for (int i = 0; i < 2 && i < static_cast<int>(maps.size()); i++)
                for (auto& q : lineQuads(maps[i])) consider(q, true);
        return x;
    }

    std::vector<Kept> pick(const std::vector<Cand>& sorted) {
        std::vector<Kept> kept;
        for (auto& c : sorted) {
            P cen = centroid(c.quad);
            bool clash = false;
            for (auto& k : kept) if (inside(cen, k.quad) || inside(centroid(k.quad), c.quad)) { clash = true; break; }
            if (!clash) kept.push_back({c.quad, c.score, c.si});
        }
        return kept;
    }

    /** Snap the outline to the edges and grow an inner panel to the card's border. */
    Quad finish(const Kept& k, std::vector<Ctx>& ctx) {
        Quad q = k.quad;
        if (refine) {
            Ctx& x = ctx[k.si];
            if (!x.rawEdges) x.rawEdges = rawEdgePoints(x.gray);
            double sc = x.scale;
            Quad qs;
            for (int i = 0; i < 4; i++) qs[i] = P(q[i].x * sc, q[i].y * sc);
            qs = refineQuad(qs, *x.rawEdges);
            if (expand) qs = refineQuad(expandToBorder(qs, x.unionBytes, x.unionMap.cols, x.unionMap.rows, x.small), *x.rawEdges);
            for (int i = 0; i < 4; i++) q[i] = P(qs[i].x / sc, qs[i].y / sc);
        }
        return q;
    }

    std::vector<Cand> sortedCands(const cv::Mat& rgb, double minAreaFrac, const Prior* prior, bool lines, std::vector<Ctx>& ctx) {
        std::vector<Cand> cands;
        for (size_t si = 0; si < workSizes.size(); si++)
            ctx.push_back(scaleCandidates(rgb, workSizes[si], minAreaFrac, prior, static_cast<int>(si), cands, lines));
        std::stable_sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score > b.score; });
        return cands;
    }
};

std::vector<Detection> CardDetector::detect(const cv::Mat& rgb, int maxCards, double minAreaFrac, const Prior* prior) {
    Impl im;
    std::vector<Ctx> ctx;
    auto sorted = im.sortedCands(rgb, minAreaFrac, prior, false, ctx);
    auto kept = im.pick(sorted);
    std::vector<Detection> out;
    for (size_t i = 0; i < kept.size() && static_cast<int>(i) < maxCards; i++)
        out.push_back({orderQuad(im.finish(kept[i], ctx)), kept[i].score});
    return out;
}

std::vector<Detection> CardDetector::hypotheses(const cv::Mat& rgb, int k, double minAreaFrac, const Prior* prior) {
    Impl im;
    std::vector<Ctx> ctx;
    auto sorted = im.sortedCands(rgb, minAreaFrac, prior, ALT_LINES, ctx);
    std::vector<Detection> chosen;
    std::vector<Cand> contoursOnly;
    for (auto& c : sorted) if (!c.fromLines) contoursOnly.push_back(c);
    auto first = im.pick(contoursOnly);
    if (!first.empty()) chosen.push_back({orderQuad(im.finish(first[0], ctx)), first[0].score});
    for (auto& c : sorted) {
        if (static_cast<int>(chosen.size()) >= k) break;
        bool near = false;
        for (auto& d : chosen) if (quadIou(c.quad, d.quad) >= 0.95) { near = true; break; }   // cheap test before snapping it
        if (near) continue;
        Quad q = im.finish(Kept{c.quad, c.score, c.si}, ctx);
        bool differs = true;
        for (auto& d : chosen) if (quadIou(q, d.quad) >= ALT_MAX_IOU) { differs = false; break; }
        if (differs) chosen.push_back({orderQuad(q), c.score});
    }
    return chosen;
}

Quad CardDetector::orderQuad(const Quad& p) {
    double cx = (p[0].x + p[1].x + p[2].x + p[3].x) / 4;
    double cy = (p[0].y + p[1].y + p[2].y + p[3].y) / 4;
    std::vector<P> s(p.begin(), p.end());
    std::stable_sort(s.begin(), s.end(), [&](const P& a, const P& b) {
        return std::atan2(a.y - cy, a.x - cx) < std::atan2(b.y - cy, b.x - cx);
    });
    int start = 0;
    for (int i = 1; i < 4; i++) if (s[i].x + s[i].y < s[start].x + s[start].y) start = i;
    Quad r;
    for (int i = 0; i < 4; i++) r[i] = s[(i + start) % 4];
    double w = (dist(r[1], r[0]) + dist(r[2], r[3])) / 2;
    double h = (dist(r[3], r[0]) + dist(r[2], r[1])) / 2;
    if (w > h) { Quad t; for (int i = 0; i < 4; i++) t[i] = r[(i + 1) % 4]; r = t; }
    return r;
}

double CardDetector::quadIou(const Quad& a, const Quad& b) {
    auto pa = toF(orderQuad(a));
    auto pb = toF(orderQuad(b));
    double inter = 0;
    try {
        std::vector<cv::Point2f> tmp;
        inter = cv::intersectConvexConvex(pa, pb, tmp, true);
    } catch (...) { inter = 0; }
    double uni = cv::contourArea(pa) + cv::contourArea(pb) - inter;
    return uni > 0 ? inter / uni : 0.0;
}

double CardDetector::polyArea(const std::vector<cv::Point2d>& q) {
    double s = 0;
    for (size_t i = 0; i < q.size(); i++) {
        const P& a = q[i];
        const P& b = q[(i + 1) % q.size()];
        s += a.x * b.y - b.x * a.y;
    }
    return std::abs(s) / 2;
}

double CardDetector::polyArea(const Quad& q) { return polyArea(std::vector<P>(q.begin(), q.end())); }

}  // namespace cardcore
