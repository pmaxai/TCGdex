// Objective-C++ between Swift and CardCore.
// OpenCV's headers come before Apple's (some of Apple's macros clash with names OpenCV uses).
#include "cardcore.hpp"
#include <opencv2/imgproc.hpp>

#import "CardCoreBridge.h"
#import <UIKit/UIKit.h>

using namespace cardcore;

static cv::Mat rgbMat(NSData *rgb, int w, int h) {
    // the bytes stay owned by the NSData for as long as the Mat is used inside one call
    return cv::Mat(h, w, CV_8UC3, const_cast<void *>(rgb.bytes));
}

static NSArray<NSValue *> *pointsOf(const Quad &q) {
    NSMutableArray *a = [NSMutableArray arrayWithCapacity:4];
    for (auto &p : q) [a addObject:[NSValue valueWithCGPoint:CGPointMake(p.x, p.y)]];
    return a;
}

static Quad quadOf(NSArray<NSValue *> *a) {
    Quad q;
    for (int i = 0; i < 4; i++) { CGPoint p = a[i].CGPointValue; q[i] = cv::Point2d(p.x, p.y); }
    return q;
}

/// the fingerprint model in Swift, behind CardCore's interface
class SwiftFingerprinter : public Fingerprinter {
public:
    explicit SwiftFingerprinter(id<CCFingerprinting> fp) : fp_(fp) {}
    std::vector<std::vector<float>> embed(const std::vector<cv::Mat> &images) override {
        if (images.empty()) return {};
        int size = images[0].cols;
        size_t px = static_cast<size_t>(size) * size * 3;
        NSMutableData *buf = [NSMutableData dataWithLength:images.size() * px];
        uint8_t *dst = static_cast<uint8_t *>(buf.mutableBytes);
        for (size_t i = 0; i < images.size(); i++) {
            cv::Mat c = images[i].isContinuous() ? images[i] : images[i].clone();
            memcpy(dst + i * px, c.data, px);
        }
        NSData *out = [fp_ embedImages:buf count:static_cast<NSInteger>(images.size()) size:size];
        std::vector<std::vector<float>> v;
        if (!out) return v;
        size_t dim = out.length / sizeof(float) / images.size();
        const float *f = static_cast<const float *>(out.bytes);
        for (size_t i = 0; i < images.size(); i++) v.emplace_back(f + i * dim, f + (i + 1) * dim);
        return v;
    }
private:
    id<CCFingerprinting> fp_;
};

@implementation CCGeometry
@end

@implementation CCHit {
@public
    Hit _h;
}
- (int)group { return _h.group; }
- (float)score { return _h.score; }
- (int)entry { return _h.entry; }
- (int)reading { return _h.reading; }
+ (instancetype)of:(const Hit &)h { CCHit *x = [CCHit new]; x->_h = h; return x; }
+ (CCHit *)hitWithGroup:(int)group score:(float)score entry:(int)entry reading:(int)reading {
    return [CCHit of:Hit{group, score, entry, reading}];
}
@end

@implementation CCRecognition {
@public
    Recognition _r;
}
- (BOOL)hasQuad { return _r.hasQuad; }
- (BOOL)detected { return _r.detected; }
- (NSArray<NSValue *> *)quad { return _r.hasQuad ? pointsOf(_r.quad) : @[]; }
- (float)margin { return _r.margin; }
- (NSArray<CCHit *> *)hits {
    NSMutableArray *a = [NSMutableArray array];
    for (auto &h : _r.hits) [a addObject:[CCHit of:h]];
    return a;
}
- (NSArray<CCHit *> *)candidates {
    NSMutableArray *a = [NSMutableArray array];
    for (auto &h : _r.candidates) [a addObject:[CCHit of:h]];
    return a;
}
- (NSDictionary<NSString *, NSNumber *> *)timingsMs {
    NSMutableDictionary *d = [NSMutableDictionary dictionary];
    for (auto &[k, v] : _r.timingsMs) d[[NSString stringWithUTF8String:k.c_str()]] = @(v);
    return d;
}
@end

@implementation CCRecognizer {
    std::unique_ptr<Recognizer> _rec;
    std::shared_ptr<Matcher> _matcher;
    NSData *_index;            // the fingerprints the matcher reads (kept alive here)
}

- (instancetype)initWithGeometry:(CCGeometry *)g index:(NSData *)index n:(int)n dim:(int)dim groups:(NSData *)groups
                      sureMargin:(float)sureMargin fingerprinter:(id<CCFingerprinting>)fingerprinter {
    if ((self = [super init])) {
        _index = index;
        Geometry geo{g.rectW, g.rectH, g.cardW, g.cardH, g.artX, g.artY, g.artW, g.artH, {g.artX0, g.artY0, g.artX1, g.artY1}, g.input};
        const int32_t *gi = static_cast<const int32_t *>(groups.bytes);
        std::vector<int> groupOf(gi, gi + n);
        _matcher = std::make_shared<Matcher>(static_cast<const float *>(index.bytes), n, dim, groupOf);
        _rec = std::make_unique<Recognizer>(geo, std::make_shared<SwiftFingerprinter>(fingerprinter), _matcher, sureMargin);
    }
    return self;
}

- (CCRecognition *)recognizeRGB:(NSData *)rgb width:(int)width height:(int)height hasPrior:(BOOL)hasPrior prior:(CCPrior)prior manual:(BOOL)manual {
    cv::Mat m = rgbMat(rgb, width, height);
    Prior p{prior.cx, prior.cy, prior.h};
    CCRecognition *r = [CCRecognition new];
    r->_r = _rec->recognise(m, hasPrior ? &p : nullptr, manual);
    return r;
}

- (void)setExcluded:(NSData *)mask {
    if (!mask) { _matcher->excluded.clear(); return; }
    const uint8_t *b = static_cast<const uint8_t *>(mask.bytes);
    _matcher->excluded.assign(mask.length, false);
    for (NSUInteger i = 0; i < mask.length; i++) _matcher->excluded[i] = b[i] != 0;
}

- (NSData *)cardImageRGB:(NSData *)rgb width:(int)width height:(int)height recognition:(CCRecognition *)r hit:(CCHit *)hit
                  margin:(double)margin outWidth:(int)ow outHeight:(int)oh {
    Quad q;
    if (!_rec->cardQuad(r->_r, hit->_h, margin, q)) return nil;
    cv::Mat card = readings::rectify(rgbMat(rgb, width, height), q, ow, oh);
    return [NSData dataWithBytes:card.data length:card.total() * 3];
}

- (NSArray<NSValue *> *)cardQuadOf:(CCRecognition *)r hit:(CCHit *)hit margin:(double)margin {
    Quad q;
    if (!_rec->cardQuad(r->_r, hit->_h, margin, q)) return nil;
    return pointsOf(q);
}

+ (NSData *)rectifyRGB:(NSData *)rgb width:(int)width height:(int)height quad:(NSArray<NSValue *> *)quad
              outWidth:(int)ow outHeight:(int)oh {
    cv::Mat card = readings::rectify(rgbMat(rgb, width, height), quadOf(quad), ow, oh);
    cv::Mat c = card.isContinuous() ? card : card.clone();
    return [NSData dataWithBytes:c.data length:c.total() * 3];
}

+ (NSArray<NSValue *> *)guideQuadWidth:(int)w height:(int)h prior:(CCPrior)prior {
    return pointsOf(readings::guideQuad(w, h, Prior{prior.cx, prior.cy, prior.h}));
}

+ (CCPrior)guidePriorWidth:(int)w height:(int)h size:(double)size {
    Prior p = readings::guidePrior(w, h, size);
    return CCPrior{p.cx, p.cy, p.h};
}
@end

@implementation CCDetector
+ (NSArray<NSArray<NSValue *> *> *)detectRGB:(NSData *)rgb width:(int)width height:(int)height maxCards:(int)maxCards {
    CardDetector d;
    NSMutableArray *a = [NSMutableArray array];
    for (auto &x : d.detect(rgbMat(rgb, width, height), maxCards)) [a addObject:pointsOf(x.quad)];
    return a;
}
+ (double)iouOf:(NSArray<NSValue *> *)a and:(NSArray<NSValue *> *)b {
    return CardDetector::quadIou(quadOf(a), quadOf(b));
}
@end
