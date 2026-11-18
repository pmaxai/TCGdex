#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * One ONNX model on ONNX Runtime's C++ API (onnxruntime.xcframework from Microsoft's download
 * server; see .github/workflows/ios.yml). Float input in, float output out. Not thread-safe:
 * the caller serialises run calls.
 */
@interface CCOnnxModel : NSObject

- (nullable instancetype)initWithPath:(NSString *)path threads:(int)threads error:(NSError **)error
    NS_SWIFT_NAME(init(path:threads:));

/// the model's first output
@property (nonatomic, readonly) NSString *outputName;

/// runs the model on one float tensor of `shape` (input `input`) and returns the first output's floats
- (nullable NSData *)runFloats:(NSData *)floats shape:(NSArray<NSNumber *> *)shape input:(NSString *)input error:(NSError **)error
    NS_SWIFT_NAME(run(floats:shape:input:));

- (instancetype)init NS_UNAVAILABLE;

@end

NS_ASSUME_NONNULL_END
