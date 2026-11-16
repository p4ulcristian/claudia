// The mic's tap (claudia.web.mic): an AudioWorklet that hands the microphone's samples to the page
// in chunks of 2048 (about 43 ms at 48 kHz), so the page can keep the last moments before a press.
class MicTap extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buf = new Float32Array(2048);
    this.at = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buf[this.at++] = ch[i];
        if (this.at === this.buf.length) {
          this.port.postMessage(this.buf, [this.buf.buffer]);
          this.buf = new Float32Array(2048);
          this.at = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor('claudia-mic-tap', MicTap);
