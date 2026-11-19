/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
#include <cstdio>
#include <vector>
extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}
// Reads raw s16le mono 16 kHz PCM from argv[1]; prints one line of 40 uint16 features per 10 ms frame.
// The microWakeWord frontend configuration (see microfrontend_vectors.py for the build).
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb");
  std::vector<int16_t> pcm; int16_t s; while (fread(&s, 2, 1, f) == 1) pcm.push_back(s);
  FrontendConfig cfg; FrontendFillConfigWithDefaults(&cfg);
  cfg.window.size_ms = 30; cfg.window.step_size_ms = 10; cfg.filterbank.num_channels = 40;
  cfg.filterbank.lower_band_limit = 125.0f; cfg.filterbank.upper_band_limit = 7500.0f;
  cfg.noise_reduction.smoothing_bits = 10; cfg.noise_reduction.even_smoothing = 0.025f; cfg.noise_reduction.odd_smoothing = 0.06f;
  cfg.noise_reduction.min_signal_remaining = 0.05f; cfg.pcan_gain_control.enable_pcan = 1; cfg.pcan_gain_control.strength = 0.95f;
  cfg.pcan_gain_control.offset = 80.0f; cfg.pcan_gain_control.gain_bits = 21; cfg.log_scale.enable_log = 1; cfg.log_scale.scale_shift = 6;
  FrontendState st; FrontendPopulateState(&cfg, &st, 16000);
  for (size_t o = 0; o + 160 <= pcm.size(); o += 160) {
    size_t read; FrontendOutput out = FrontendProcessSamples(&st, &pcm[o], 160, &read);
    if (!out.size) continue;
    for (size_t i = 0; i < out.size; i++) printf(i ? " %u" : "%u", out.values[i]);
    printf("\n");
  }
}
