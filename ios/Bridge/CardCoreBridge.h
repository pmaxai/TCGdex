// The Objective-C face of CardCore (C++) for Swift. Images cross as tightly packed RGB bytes.
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// The fingerprint model, run by Swift (ONNX Runtime): count images of size x size x 3 RGB
/// bytes in, count x dim floats (unit vectors) out.
@protocol CCFingerprinting <NSObject>
- (nullable NSData *)embedImages:(NSData *)rgb count:(NSInteger)count size:(NSInteger)size;
@end

/// Image geometry from the data pack's meta table.
@interface CCGeometry : NSObject
@property (nonatomic) int rectW, rectH, cardW, cardH, artX, artY, artW, artH, input;
@property (nonatomic) double artX0, artY0, artX1, artY1;
@end

@interface CCHit : NSObject
/// the artwork group's number (index into the pack's group list)
@property (nonatomic, readonly) int group;
@property (nonatomic, readonly) float score;
/// the reference entry (index into the pack's entries)
@property (nonatomic, readonly) int entry;
/// 0 card, 1 card upside down, 2 artwork, 3 artwork upside down
@property (nonatomic, readonly) int reading;
/// a hit read another way (e.g. the artwork upside down)
+ (CCHit *)hitWithGroup:(int)group score:(float)score entry:(int)entry reading:(int)reading
    NS_SWIFT_NAME(make(group:score:entry:reading:));
@end

@interface CCRecognition : NSObject
@property (nonatomic, readonly) BOOL hasQuad;
@property (nonatomic, readonly) BOOL detected;
/// tl, tr, br, bl in image pixels (empty without an outline)
@property (nonatomic, readonly) NSArray<NSValue *> *quad;
@property (nonatomic, readonly) NSArray<CCHit *> *hits;
@property (nonatomic, readonly) NSArray<CCHit *> *candidates;
@property (nonatomic, readonly) float margin;
@property (nonatomic, readonly) NSDictionary<NSString *, NSNumber *> *timingsMs;
@end

/// Where the guide frame expects the card (fractions of the image).
typedef struct { double cx, cy, h; } CCPrior;

@interface CCRecognizer : NSObject
/// index: n x dim float32 fingerprints; groups: n int32 group numbers
- (instancetype)initWithGeometry:(CCGeometry *)geometry index:(NSData *)index n:(int)n dim:(int)dim
                          groups:(NSData *)groups sureMargin:(float)sureMargin fingerprinter:(id<CCFingerprinting>)fingerprinter;
- (CCRecognition *)recognizeRGB:(NSData *)rgb width:(int)width height:(int)height
                       hasPrior:(BOOL)hasPrior prior:(CCPrior)prior manual:(BOOL)manual;
/// entries never to return (nil: none); n bytes, non-zero = skip
- (void)setExcluded:(nullable NSData *)mask;
/// The card of a reading, straightened and upright as RGB bytes (width x height x 3), for reading its
/// text or showing it. margin: room around a card rebuilt from its artwork box. nil without an outline.
- (nullable NSData *)cardImageRGB:(NSData *)rgb width:(int)width height:(int)height recognition:(CCRecognition *)r
                              hit:(CCHit *)hit margin:(double)margin outWidth:(int)ow outHeight:(int)oh;
/// The card's outer corners of a reading (tl, tr, br, bl in the recognised image's pixels); nil without an outline.
- (nullable NSArray<NSValue *> *)cardQuadOf:(CCRecognition *)r hit:(CCHit *)hit margin:(double)margin
    NS_SWIFT_NAME(cardQuad(of:hit:margin:));
/// The part of an image inside a quad (tl, tr, br, bl), straightened to ow x oh, as RGB bytes (product pictures).
+ (NSData *)rectifyRGB:(NSData *)rgb width:(int)width height:(int)height quad:(NSArray<NSValue *> *)quad
              outWidth:(int)ow outHeight:(int)oh;
/// The guide frame quad for an image of this size.
+ (NSArray<NSValue *> *)guideQuadWidth:(int)w height:(int)h prior:(CCPrior)prior;
+ (CCPrior)guidePriorWidth:(int)w height:(int)h size:(double)size;
@end

@interface CCDetector : NSObject
/// The detector alone (tests): up to maxCards outlines, each 4 CGPoints.
+ (NSArray<NSArray<NSValue *> *> *)detectRGB:(NSData *)rgb width:(int)width height:(int)height maxCards:(int)maxCards;
/// overlap of two outlines (0..1)
+ (double)iouOf:(NSArray<NSValue *> *)a and:(NSArray<NSValue *> *)b NS_SWIFT_NAME(iou(_:_:));
@end

NS_ASSUME_NONNULL_END
