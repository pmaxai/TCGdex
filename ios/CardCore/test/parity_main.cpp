// CardCore parity on Linux: the detector on the synthetic photos and the whole recognition on
// the real photos, against what the Python pipeline saw (parity.json from the data pack).
//   parity <pack dir> <parity dir>      (exit 1 on a difference)
#include "cardcore.hpp"
#include "pack_loader.hpp"

#include <onnxruntime_cxx_api.h>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <cstdio>
#include <fstream>
#include <sstream>

#include "json.hpp"   // tiny JSON reader (test only)

using namespace cardcore;

/** the fingerprint model with ONNX Runtime's C++ API (on the iPhone: its Objective-C API) */
class OrtFingerprinter : public Fingerprinter {
public:
    OrtFingerprinter(const std::string& path, int size) : env_(ORT_LOGGING_LEVEL_WARNING, "parity"), size_(size) {
        Ort::SessionOptions o;
        o.SetIntraOpNumThreads(2);
        o.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        session_ = std::make_unique<Ort::Session>(env_, path.c_str(), o);
        Ort::AllocatorWithDefaultOptions a;
        out_ = session_->GetOutputNameAllocated(0, a).get();
    }
    std::vector<std::vector<float>> embed(const std::vector<cv::Mat>& images) override {
        size_t px = static_cast<size_t>(size_) * size_ * 3;
        std::vector<float> buf(images.size() * px);
        for (size_t i = 0; i < images.size(); i++) {
            const cv::Mat& m = images[i];
            cv::Mat c = m.isContinuous() ? m : m.clone();
            for (size_t j = 0; j < px; j++) buf[i * px + j] = c.data[j];
        }
        std::array<int64_t, 4> shape{static_cast<int64_t>(images.size()), size_, size_, 3};
        auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        Ort::Value in = Ort::Value::CreateTensor<float>(mem, buf.data(), buf.size(), shape.data(), shape.size());
        const char* inName = "image";
        const char* outName = out_.c_str();
        auto res = session_->Run(Ort::RunOptions{nullptr}, &inName, &in, 1, &outName, 1);
        auto info = res[0].GetTensorTypeAndShapeInfo().GetShape();
        int dim = static_cast<int>(info[1]);
        const float* o = res[0].GetTensorData<float>();
        std::vector<std::vector<float>> v;
        for (size_t i = 0; i < images.size(); i++) v.emplace_back(o + i * dim, o + (i + 1) * dim);
        return v;
    }
private:
    Ort::Env env_;
    std::unique_ptr<Ort::Session> session_;
    std::string out_;
    int size_;
};

static cv::Mat loadRgb(const std::string& path) {
    cv::Mat bgr = cv::imread(path, cv::IMREAD_COLOR);          // turned upright by its EXIF orientation
    cv::Mat rgb;
    if (!bgr.empty()) cv::cvtColor(bgr, rgb, cv::COLOR_BGR2RGB);
    return rgb;
}

static Quad quadOf(const json::Value& a) {
    Quad q;
    for (int i = 0; i < 4; i++) q[i] = cv::Point2d(a[i][0].num(), a[i][1].num());
    return q;
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "parity <pack dir> <parity dir>\n"); return 2; }
    std::string packDir = argv[1], parDir = argv[2];
    std::ifstream f(parDir + "/parity.json");
    std::stringstream ss; ss << f.rdbuf();
    json::Value fx = json::parse(ss.str());
    int failures = 0;

    // ---- the detector on the synthetic photos
    CardDetector det;
    auto& syn = fx["synthetic"];
    int agree = 0;
    for (size_t i = 0; i < syn.size(); i++) {
        auto& it = syn[i];
        cv::Mat img = loadRgb(parDir + "/photos/" + it["file"].str());
        auto d = det.detect(img, 1);
        bool py = it["detected"].boolean();
        double iou = (py && !d.empty()) ? CardDetector::quadIou(d[0].quad, quadOf(it["quad"])) : 0;
        bool ok = py ? (!d.empty() && iou >= 0.9) : d.empty();
        if (ok) agree++;
        std::printf("synthetic %s: python=%d app=%d overlap=%.3f %s\n", it["file"].str().c_str(), py, !d.empty(), iou, ok ? "OK" : "DIFF");
    }
    std::printf("detector agrees on %d/%zu synthetic photos\n", agree, syn.size());
    if (agree < static_cast<int>(syn.size()) - 1) failures++;

    // ---- the whole recognition on the real photos
    PackData pack = loadPack(packDir);
    auto fp = std::make_shared<OrtFingerprinter>(packDir + "/model.onnx", pack.geometry.input);
    auto matcher = std::make_shared<Matcher>(pack.index.data(), pack.n, pack.dim, pack.groupIndex);
    Recognizer rec(pack.geometry, fp, matcher, pack.sureMargin);
    auto& real = fx["real"];
    int same = 0, n = 0;
    for (size_t i = 0; i < real.size(); i++) {
        auto& it = real[i];
        if (!it.has("top") || it["top"].size() == 0) continue;
        std::string file = it["file"].str();
        cv::Mat img = loadRgb(parDir + "/photos/" + file);
        if (img.empty()) img = loadRgb(parDir + "/real/" + file);
        if (img.empty()) { std::printf("real %s: missing\n", file.c_str()); continue; }
        auto r = rec.recognise(img, nullptr);
        n++;
        std::string want = it["top"][0]["id"].str();
        double wantScore = it["top"][0]["score"].num();
        std::string got = r.hits.empty() ? "-" : pack.cardOf[r.hits[0].entry];
        double gotScore = r.hits.empty() ? 0 : r.hits[0].score;
        bool ok = !r.hits.empty() && pack.groupName[r.hits[0].group] == pack.groupOfCard(want);
        if (ok) same++;
        std::printf("real %s: python %s %.4f  app %s %.4f  margin %.4f/%.4f  detect %.0fms model %.0fms %s\n", file.c_str(),
                    want.c_str(), wantScore, got.c_str(), gotScore, it["margin"].num(), r.margin,
                    r.timingsMs["detect"], r.timingsMs["model"], ok ? "OK" : "DIFF");
    }
    std::printf("recognition agrees on %d/%d real photos\n", same, n);
    if (same < n) failures++;
    return failures ? 1 : 0;
}
