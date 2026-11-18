// ONNX Runtime's C++ API first (from Vendor/onnxruntime-headers), then Apple's headers.
#include "onnxruntime_cxx_api.h"

#include <memory>
#include <string>
#include <vector>

#import "OnnxModel.h"

static NSError *OrtError(const Ort::Exception &e) {
    return [NSError errorWithDomain:@"OnnxRuntime" code:(NSInteger)e.GetOrtErrorCode()
                           userInfo:@{NSLocalizedDescriptionKey: @(e.what())}];
}

@implementation CCOnnxModel {
    std::unique_ptr<Ort::Env> _env;
    std::unique_ptr<Ort::Session> _session;
    std::string _output;
}

- (instancetype)initWithPath:(NSString *)path threads:(int)threads error:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    try {
        _env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "fingerprint");
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        _session = std::make_unique<Ort::Session>(*_env, path.fileSystemRepresentation, options);
        Ort::AllocatorWithDefaultOptions allocator;
        if (_session->GetOutputCount() > 0) {
            _output = _session->GetOutputNameAllocated(0, allocator).get();
        } else {
            _output = "embedding";
        }
    } catch (const Ort::Exception &e) {
        if (error) *error = OrtError(e);
        return nil;
    }
    return self;
}

- (NSString *)outputName {
    return @(_output.c_str());
}

- (NSData *)runFloats:(NSData *)floats shape:(NSArray<NSNumber *> *)shape input:(NSString *)input error:(NSError **)error {
    try {
        std::vector<int64_t> dims;
        for (NSNumber *n in shape) dims.push_back(n.longLongValue);
        Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        // the tensor reads the caller's floats in place (ONNX Runtime doesn't write to an input)
        Ort::Value tensor = Ort::Value::CreateTensor<float>(memory, (float *)floats.bytes, floats.length / sizeof(float),
                                                            dims.data(), dims.size());
        const char *inputs[] = {input.UTF8String};
        const char *outputs[] = {_output.c_str()};
        std::vector<Ort::Value> out = _session->Run(Ort::RunOptions{nullptr}, inputs, &tensor, 1, outputs, 1);
        if (out.empty() || !out[0].IsTensor()) {
            if (error) *error = [NSError errorWithDomain:@"OnnxRuntime" code:-1 userInfo:@{NSLocalizedDescriptionKey: @"no tensor output"}];
            return nil;
        }
        size_t count = out[0].GetTensorTypeAndShapeInfo().GetElementCount();
        return [NSData dataWithBytes:out[0].GetTensorData<float>() length:count * sizeof(float)];
    } catch (const Ort::Exception &e) {
        if (error) *error = OrtError(e);
        return nil;
    }
}

@end
