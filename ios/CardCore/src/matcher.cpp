// Matcher and Recognizer: ports of Matcher.kt and Recognizer.kt.
#include "cardcore.hpp"

#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <thread>
#include <unordered_set>

namespace cardcore {

namespace {
constexpr float NEG = -std::numeric_limits<float>::infinity();

template <typename F>
void parallel(int count, F body) {
    int threads = std::clamp(static_cast<int>(std::thread::hardware_concurrency()), 1, 4);
    int chunk = (count + threads - 1) / threads;
    std::vector<std::thread> ts;
    for (int t = 0; t < threads; t++) {
        int from = t * chunk, to = std::min(count, (t + 1) * chunk);
        if (from >= to) break;
        ts.emplace_back([=] { body(from, to); });
    }
    for (auto& t : ts) t.join();
}
}  // namespace

Matcher::Matcher(const float* index, int n, int dim, std::vector<int> groupOf, int coarseDim, int shortlist)
    : index_(index), n_(n), dim_(dim), coarseDim_(coarseDim), shortlist_(shortlist), groupOf_(std::move(groupOf)) {}

float Matcher::score(int i, const std::vector<std::vector<float>>& readings, int d, int* which) const {
    const float* row = index_ + static_cast<size_t>(i) * dim_;
    float bs = NEG;
    int bw = 0;
    for (size_t r = 0; r < readings.size(); r++) {
        const float* q = readings[r].data();
        float s = 0;
        for (int k = 0; k < d; k++) s += q[k] * row[k];
        if (s > bs) { bs = s; bw = static_cast<int>(r); }
    }
    if (which) which[i] = bw;
    return bs;
}

std::vector<int> Matcher::topIndices(const std::vector<float>& v, int m, const std::vector<int>* among) {
    int cnt = among ? static_cast<int>(among->size()) : static_cast<int>(v.size());
    int size = std::min(m, cnt);
    if (size <= 0) return {};
    std::vector<int> idx;
    idx.reserve(cnt);
    for (int j = 0; j < cnt; j++) idx.push_back(among ? (*among)[j] : j);
    // largest first; ties: lower index first (like a stable sort)
    auto better = [&](int a, int b) { return v[a] != v[b] ? v[a] > v[b] : a < b; };
    std::partial_sort(idx.begin(), idx.begin() + size, idx.end(), better);
    idx.resize(size);
    return idx;
}

std::vector<Hit> Matcher::search(const std::vector<std::vector<float>>& readings, int k, int pool) const {
    std::vector<float> best(n_, NEG);
    std::vector<int> which(n_, 0);
    int cd = std::min(coarseDim_, dim_);
    bool ex = static_cast<int>(excluded.size()) == n_;
    std::vector<int> candidates;
    if (n_ <= shortlist_ || cd == dim_) {
        parallel(n_, [&](int from, int to) {
            for (int i = from; i < to; i++) best[i] = (ex && excluded[i]) ? NEG : score(i, readings, dim_, which.data());
        });
        candidates.resize(n_);
        for (int i = 0; i < n_; i++) candidates[i] = i;
    } else {
        std::vector<float> rough(n_);
        parallel(n_, [&](int from, int to) {
            for (int i = from; i < to; i++) rough[i] = (ex && excluded[i]) ? NEG : score(i, readings, cd, nullptr);
        });
        candidates = topIndices(rough, shortlist_);
        parallel(static_cast<int>(candidates.size()), [&](int from, int to) {
            for (int j = from; j < to; j++) { int i = candidates[j]; best[i] = score(i, readings, dim_, which.data()); }
        });
    }
    // best `pool + 1` entries, then distinct cards in score order
    auto top = topIndices(best, std::min(pool + 1, static_cast<int>(candidates.size())), &candidates);
    std::unordered_set<int> seen;
    std::vector<Hit> out;
    for (int i : top) {
        if (best[i] == NEG) continue;
        int g = groupOf_[i];
        if (!seen.insert(g).second) continue;
        out.push_back({g, best[i], i, which[i]});
        if (static_cast<int>(out.size()) > k) break;
    }
    return out;
}

// ---------------------------------------------------------------- recognizer

Recognizer::Recognizer(Geometry g, std::shared_ptr<Fingerprinter> fp, std::shared_ptr<Matcher> matcher, float sureMargin)
    : g_(g), fp_(std::move(fp)), matcher_(std::move(matcher)), sureMargin_(sureMargin) {}

Recognizer::Read Recognizer::read(const cv::Mat& rgb, const Quad& quad, int k, int pool, std::map<std::string, double>& t) {
    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();
    auto lap = [&](const char* name) {
        auto now = clock::now();
        t[name] += std::chrono::duration<double, std::milli>(now - t0).count();
        t0 = now;
    };
    cv::Mat card = readings::toCard(rgb, quad, g_);
    cv::Mat card180 = readings::rot180(card);
    auto art = readings::asArtwork(rgb, quad, g_);
    std::vector<cv::Mat> inputs;
    for (const cv::Mat* m : {&card, &card180, &art.first, &art.second}) inputs.push_back(readings::artInput(*m, g_));
    lap("readings");
    auto fps = fp_->embed(inputs);
    lap("model");
    auto hits = matcher_->search(fps, k, pool);
    lap("search");
    float margin = hits.size() > 1 ? hits[0].score - hits[1].score : (hits.empty() ? 0.f : hits[0].score);
    return {quad, hits, margin};
}

Recognition Recognizer::recognise(const cv::Mat& rgb, const Prior* prior, bool manual) {
    Recognition rec;
    auto t0 = std::chrono::steady_clock::now();
    auto dets = detector_.hypotheses(rgb, HYPOTHESES, 0.03, prior);
    rec.timingsMs["detect"] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    // taken by hand: the guide frame itself is one more guess (the user put the card there)
    std::vector<Quad> quads;
    for (auto& d : dets) quads.push_back(d.quad);
    if (prior) {
        Quad guide = readings::guideQuad(rgb.cols, rgb.rows, *prior);
        if (manual || quads.empty()) quads.push_back(guide);
    }
    if (quads.empty()) return rec;
    int k = manual ? MANUAL_CANDIDATES : 5;
    std::map<int, Hit> all;
    bool have = false;
    Read best;
    bool bestIsOutline = false;
    for (size_t i = 0; i < quads.size(); i++) {
        Read r = read(rgb, quads[i], k, manual ? 160 : 40, rec.timingsMs);
        if (manual) for (auto& h : r.hits) { auto it = all.find(h.group); if (it == all.end() || it->second.score < h.score) all[h.group] = h; }
        float rs = r.hits.empty() ? -1.f : r.hits[0].score;
        float bs = (!have || best.hits.empty()) ? -1.f : best.hits[0].score;
        if (!have || rs > bs) { best = r; have = true; bestIsOutline = i < dets.size(); }
        if (!manual && r.margin >= sureMargin_) break;
    }
    rec.quad = best.quad;
    rec.hasQuad = true;
    rec.detected = bestIsOutline;
    rec.hits = best.hits;
    rec.margin = best.margin;
    if (manual) {
        std::vector<Hit> c;
        for (auto& [g, h] : all) c.push_back(h);
        std::stable_sort(c.begin(), c.end(), [](const Hit& a, const Hit& b) { return a.score > b.score; });
        if (static_cast<int>(c.size()) > MANUAL_CANDIDATES) c.resize(MANUAL_CANDIDATES);
        rec.candidates = c;
    } else rec.candidates = rec.hits;
    return rec;
}

bool Recognizer::cardQuad(const Recognition& r, const Hit& hit, double margin, Quad& out) const {
    if (!r.hasQuad) return false;
    const Quad& q = r.quad;
    switch (hit.reading) {
        case 0: out = q; break;
        case 1: out = Quad{q[2], q[3], q[0], q[1]}; break;
        case 2: out = readings::cardQuadFromArtwork(q, false, g_, margin); break;
        default: out = readings::cardQuadFromArtwork(q, true, g_, margin); break;
    }
    return true;
}

}  // namespace cardcore
