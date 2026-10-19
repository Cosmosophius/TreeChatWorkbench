// ttocr — macOS Vision OCR CLI（条畅桌面版用）
// 用法：ttocr <image.png>  → stdout: JSON 数组 [{"text":"..","x":..,"y":..,"w":..,"h":..}]（归一化坐标）
#import <Foundation/Foundation.h>
#import <Vision/Vision.h>
#import <AppKit/AppKit.h>

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        if (argc < 2) { fprintf(stderr, "usage: ttocr image.png\n"); return 2; }
        NSString *path = [NSString stringWithUTF8String:argv[1]];
        NSImage *image = [[NSImage alloc] initWithContentsOfFile:path];
        if (image == nil) { fprintf(stderr, "cannot open image\n"); return 2; }
        CGImageSourceRef src = CGImageSourceCreateWithURL((CFURLRef)[NSURL fileURLWithPath:path], NULL);
        CGImageRef cg = CGImageSourceCreateImageAtIndex(src, 0, NULL);
        if (cg == NULL) { fprintf(stderr, "cannot decode image\n"); return 2; }

        VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:cg options:@{}];
        VNRecognizeTextRequest *req = [[VNRecognizeTextRequest alloc] initWithCompletionHandler:^(VNRequest *request, NSError *error) {
            if (error != nil) { return; }
            NSMutableArray *out = [NSMutableArray array];
            for (VNObservation *obs in request.results) {
                if (![obs isKindOfClass:[VNRecognizedTextObservation class]]) continue;
                VNRecognizedTextObservation *textObs = (VNRecognizedTextObservation *)obs;
                VNRecognizedText *candidate = [textObs topCandidates:1].firstObject;
                if (candidate == nil) continue;
                CGRect box = textObs.boundingBox; // 归一化，原点左下
                NSDictionary *d = @{
                    @"text": candidate.string,
                    @"x": @(box.origin.x),
                    @"y": @(1.0 - box.origin.y - box.size.height), // 翻成自顶
                    @"w": @(box.size.width),
                    @"h": @(box.size.height),
                };
                [out addObject:d];
            }
            NSData *json = [NSJSONSerialization dataWithJSONObject:out options:0 error:nil];
            printf("%s\n", [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding].UTF8String);
        }];
        req.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
        req.usesLanguageCorrection = NO;
        req.recognitionLanguages = @[@"zh-Hans", @"en-US", @"ru-RU"];
        [handler performRequests:@[req] error:nil];
        CFRelease(cg); CFRelease(src);
        return 0;
    }
}
