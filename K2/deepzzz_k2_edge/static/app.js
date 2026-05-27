const statusEl = document.getElementById("status");
const snapshotEl = document.getElementById("snapshot");
const videoEl = document.getElementById("video");
const overlayEl = document.getElementById("overlay");
const poseModelEl = document.getElementById("poseModel");
const previewMetaEl = document.getElementById("previewMeta");
const lowresMetaEl = document.getElementById("lowresMeta");
const personsSummaryEl = document.getElementById("personsSummary");
const latencySummaryEl = document.getElementById("latencySummary");
const cpuSummaryEl = document.getElementById("cpuSummary");
const appSummaryEl = document.getElementById("appSummary");
const audioPlayerEl = document.getElementById("audioPlayer");

const runtimeFields = {
  camera: document.getElementById("runtimeCamera"),
  device: document.getElementById("runtimeDevice"),
  hls: document.getElementById("runtimeHls"),
  preview: document.getElementById("runtimePreview"),
  analysis: document.getElementById("runtimeAnalysis"),
  frames: document.getElementById("runtimeFrames"),
  vision: document.getElementById("runtimeVision"),
  audio: document.getElementById("runtimeAudio"),
};

const resourceFields = {
  cpu: document.getElementById("resourceCpu"),
  load: document.getElementById("resourceLoad"),
  memory: document.getElementById("resourceMemory"),
  temp: document.getElementById("resourceTemp"),
  app: document.getElementById("resourceApp"),
  ffmpeg: document.getElementById("resourceFfmpeg"),
};

const audioFields = {
  status: document.getElementById("audioStatus"),
  engine: document.getElementById("audioEngine"),
  duration: document.getElementById("audioDuration"),
  rms: document.getElementById("audioRms"),
  peak: document.getElementById("audioPeak"),
  dbfs: document.getElementById("audioDbfs"),
  zcr: document.getElementById("audioZcr"),
};

const yamnetFields = {
  status: document.getElementById("yamnetStatus"),
  cryScore: document.getElementById("yamnetCryScore"),
  top: document.getElementById("yamnetTop"),
  latency: document.getElementById("yamnetLatency"),
};

const coverPanelEl = document.getElementById("faceCoverPanel");
const coverFields = {
  status: document.getElementById("coverStatus"),
  reason: document.getElementById("coverReason"),
  visiblePoints: document.getElementById("coverVisiblePoints"),
  nose: document.getElementById("coverNose"),
  leftEye: document.getElementById("coverLeftEye"),
  rightEye: document.getElementById("coverRightEye"),
  leftEar: document.getElementById("coverLeftEar"),
  rightEar: document.getElementById("coverRightEar"),
};

let latestDetections = null;
let hls = null;

const POSE_CONNECTIONS = [
  [16, 14], [14, 12], [15, 13], [13, 11], [12, 11],
  [5, 7], [7, 9], [6, 8], [8, 10],
  [5, 6], [5, 11], [6, 12],
  [11, 13], [12, 14],
  [0, 1], [0, 2], [1, 3], [2, 4],
  [0, 5], [0, 6],
  [3, 5], [4, 6],
];

const STANDARD_POSE = [
  [130, 28],
  [114, 22],
  [146, 22],
  [96, 33],
  [164, 33],
  [82, 82],
  [178, 82],
  [56, 132],
  [204, 132],
  [44, 186],
  [216, 186],
  [96, 168],
  [164, 168],
  [88, 218],
  [172, 218],
  [78, 250],
  [182, 250],
];

const CONFIDENCE_LABEL_OFFSETS = [
  [8, -17],
  [-48, -10],
  [14, -10],
  [-56, 4],
  [20, 4],
  [-62, -5],
  [20, -5],
  [-54, -4],
  [20, -4],
  [-26, 20],
  [-18, 20],
  [-60, -2],
  [20, -2],
  [-58, 3],
  [20, 3],
  [-52, 2],
  [18, 2],
];

function setStatus(text) {
  statusEl.textContent = text;
}

function connectHls() {
  const source = `/hls/stream.m3u8?t=${Date.now()}`;
  if (hls) {
    hls.destroy();
    hls = null;
  }
  if (window.Hls && Hls.isSupported()) {
    hls = new Hls({
      liveSyncDurationCount: 3,
      liveMaxLatencyDurationCount: 6,
      lowLatencyMode: true,
      maxLiveSyncPlaybackRate: 1.1,
    });
    hls.loadSource(source);
    hls.attachMedia(videoEl);
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setStatus("Live preview connected");
      videoEl.play().catch(() => setStatus("Tap play"));
    });
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        setStatus("Preview reconnecting");
        hls.startLoad();
      }
    });
  } else if (videoEl.canPlayType("application/vnd.apple.mpegurl")) {
    videoEl.src = source;
    videoEl.addEventListener("loadedmetadata", () => videoEl.play(), { once: true });
  } else {
    setStatus("HLS unsupported by browser");
  }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    cache: "no-store",
    ...options,
  });
  const payload = await response.json();
  if (!payload.ok) {
    throw new Error(payload.error || `request failed: ${path}`);
  }
  return payload;
}

function renderRuntime(status) {
  const preview = `${status.config.preview_size} @ ${status.config.preview_fps}fps`;
  runtimeFields.camera.textContent = status.camera.running ? "running" : "stopped";
  runtimeFields.device.textContent = status.camera.device || "-";
  runtimeFields.hls.textContent = status.camera.hls_ready ? "ready" : "waiting";
  runtimeFields.preview.textContent = preview;
  runtimeFields.analysis.textContent = `${status.config.analysis_width}px @ ${status.config.analysis_fps}fps`;
  runtimeFields.frames.textContent = `${status.camera.frames} captured / ${status.vision.processed} processed`;
  runtimeFields.vision.textContent = status.vision.running ? status.vision.engine : "stopped";
  runtimeFields.audio.textContent = status.config.audio_device;
  previewMetaEl.textContent = formatFps(status.config.preview_fps);
  lowresMetaEl.textContent = formatFps(status.config.analysis_fps);
}

function renderResources(resources) {
  if (!resources) {
    resourceFields.cpu.textContent = "waiting";
    return;
  }
  const memory = resources.memory || {};
  const load = resources.loadavg || [];
  const processes = resources.processes || [];
  const appProcess = processes.find((process) => process.name === "app.py");
  const ffmpegProcess = processes.find((process) => process.name === "ffmpeg");
  resourceFields.cpu.textContent = resources.cpu_percent == null ? "warming up" : `${resources.cpu_percent}% / ${resources.cpu_count} cores`;
  resourceFields.load.textContent = load.length ? load.join(" / ") : "-";
  resourceFields.memory.textContent = memory.total_mb ? `${memory.used_mb} / ${memory.total_mb} MB (${memory.percent}%)` : "-";
  resourceFields.temp.textContent = resources.temperature_c == null ? "-" : `${resources.temperature_c} C`;
  resourceFields.app.textContent = formatProcess(appProcess);
  resourceFields.ffmpeg.textContent = formatProcess(ffmpegProcess);
  cpuSummaryEl.textContent = resources.cpu_percent == null ? "-" : `${resources.cpu_percent}%`;
  appSummaryEl.textContent = appProcess && appProcess.cpu_percent != null ? `${appProcess.cpu_percent}%` : "-";
}

function renderMetrics(result) {
  if (!result) {
    personsSummaryEl.textContent = "-";
    latencySummaryEl.textContent = "-";
    return;
  }
  personsSummaryEl.textContent = result.persons ? String(result.persons.length) : "0";
  latencySummaryEl.textContent = result.elapsed_ms == null ? "-" : `${Math.round(result.elapsed_ms)} ms`;
}

function formatFps(value) {
  if (value == null || value === "") return "waiting";
  const number = Number(value);
  if (Number.isFinite(number)) {
    return `${number} fps`;
  }
  return `${value} fps`;
}

function formatProcess(process) {
  if (!process) return "-";
  const cpu = process.cpu_percent == null ? "warming" : `${process.cpu_percent}%`;
  return `${cpu}, ${process.rss_mb} MB`;
}

function drawPoseModel(result) {
  const ctx = poseModelEl.getContext("2d");
  const width = poseModelEl.width;
  const height = poseModelEl.height;
  const person = result && result.persons && result.persons.length ? result.persons[0] : null;
  const keypoints = person ? person.keypoints : [];

  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = "#080a0c";
  ctx.fillRect(0, 0, width, height);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.lineWidth = 4;

  for (const [start, end] of POSE_CONNECTIONS) {
    const active = pointConfidence(keypoints, start) >= 0.25 && pointConfidence(keypoints, end) >= 0.25;
    ctx.strokeStyle = active ? "#e9c46a" : "#3a444d";
    ctx.beginPath();
    ctx.moveTo(STANDARD_POSE[start][0], STANDARD_POSE[start][1]);
    ctx.lineTo(STANDARD_POSE[end][0], STANDARD_POSE[end][1]);
    ctx.stroke();
  }

  for (let index = 0; index < STANDARD_POSE.length; index += 1) {
    const confidence = pointConfidence(keypoints, index);
    const active = confidence >= 0.25;
    const face = index <= 4;
    const [x, y] = STANDARD_POSE[index];
    ctx.fillStyle = active ? (face ? "#5ec8a5" : "#e9c46a") : "#46525c";
    ctx.beginPath();
    ctx.arc(x, y, active ? 7 : 6, 0, Math.PI * 2);
    ctx.fill();
    drawConfidenceLabel(ctx, index, confidence, active, face);
  }
}

function renderFaceCover(result) {
  const person = result && result.persons && result.persons.length ? result.persons[0] : null;
  if (!person || !person.keypoints) {
    setCoverPanel("caution", "No person", "No pose result yet", {});
    return;
  }

  // YOLOv8-pose face keypoints:
  // 0 = nose, 1 = left eye, 2 = right eye, 3 = left ear, 4 = right ear.
  // A keypoint is treated as visible when confidence >= FACE_POINT_THRESHOLD.
  // Tune FACE_POINT_THRESHOLD or the branch rules below if later tests show
  // the camera angle, bedding, or lighting makes the current rule too strict.
  const face = {
    nose: facePoint(person.keypoints, 0),
    leftEye: facePoint(person.keypoints, 1),
    rightEye: facePoint(person.keypoints, 2),
    leftEar: facePoint(person.keypoints, 3),
    rightEar: facePoint(person.keypoints, 4),
  };

  // Front-face evidence: nose or either eye is visible.
  // Ear-only evidence: no nose/eye is visible, but at least one ear is visible.
  // Current rule:
  // - frontVisible => normal face-visible state.
  // - !frontVisible && earVisible => suspected covered face / rollover.
  // - !frontVisible && !earVisible => suspected covered face / rollover.
  // If you later want fewer false alarms, this is the main decision block to adjust.
  const frontVisible = face.nose.present || face.leftEye.present || face.rightEye.present;
  const earVisible = face.leftEar.present || face.rightEar.present;
  const visibleNames = Object.entries(face).filter(([, point]) => point.present).map(([name]) => name);

  let mode = "clear";
  let status = "Face visible";
  let reason = "Nose or eye point is visible";
  if (!frontVisible && earVisible) {
    mode = "covered";
    status = "Suspected face cover / rollover";
    reason = "Nose and eyes are missing; only ear point is visible";
  } else if (!frontVisible && !earVisible) {
    mode = "covered";
    status = "Suspected face cover / rollover";
    reason = "No face keypoint is visible";
  }

  setCoverPanel(mode, status, reason, face, visibleNames);
}

const FACE_POINT_THRESHOLD = 0.25;

function facePoint(keypoints, index) {
  const point = keypoints[index] || [0, 0, 0];
  const confidence = Number(point[2] || 0);
  return { confidence, present: confidence >= FACE_POINT_THRESHOLD };
}

function setCoverPanel(mode, status, reason, face, visibleNames = []) {
  coverPanelEl.classList.remove("covered", "clear", "caution");
  coverPanelEl.classList.add(mode);
  coverFields.status.textContent = status;
  coverFields.reason.textContent = reason;
  coverFields.visiblePoints.textContent = visibleNames.length ? visibleNames.join(", ") : "none";
  coverFields.nose.textContent = formatFacePoint(face.nose);
  coverFields.leftEye.textContent = formatFacePoint(face.leftEye);
  coverFields.rightEye.textContent = formatFacePoint(face.rightEye);
  coverFields.leftEar.textContent = formatFacePoint(face.leftEar);
  coverFields.rightEar.textContent = formatFacePoint(face.rightEar);
}

function formatFacePoint(point) {
  if (!point) return "-";
  return `${point.present ? "visible" : "missing"} (${point.confidence.toFixed(2)})`;
}

function drawConfidenceLabel(ctx, index, confidence, active, face) {
  const [x, y] = STANDARD_POSE[index];
  const [dx, dy] = CONFIDENCE_LABEL_OFFSETS[index];
  const text = confidence.toFixed(2);
  const labelX = x + dx;
  const labelY = y + dy;
  ctx.font = "11px system-ui, sans-serif";
  ctx.textAlign = "left";
  ctx.textBaseline = "middle";
  const boxWidth = ctx.measureText(text).width + 8;
  const boxHeight = 16;
  ctx.fillStyle = active ? "rgba(32, 38, 42, 0.94)" : "rgba(18, 22, 25, 0.9)";
  roundRect(ctx, labelX - 4, labelY - boxHeight / 2, boxWidth, boxHeight, 4);
  ctx.fill();
  ctx.fillStyle = active ? (face ? "#5ec8a5" : "#e9c46a") : "#88939d";
  ctx.fillText(text, labelX, labelY + 0.5);
}

function roundRect(ctx, x, y, width, height, radius) {
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + width - radius, y);
  ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
  ctx.lineTo(x + width, y + height - radius);
  ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
  ctx.lineTo(x + radius, y + height);
  ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
  ctx.closePath();
}

function pointConfidence(keypoints, index) {
  const point = keypoints[index];
  return point ? Number(point[2] || 0) : 0;
}

function drawOverlay() {
  const ctx = overlayEl.getContext("2d");
  const rect = overlayEl.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  overlayEl.width = Math.max(1, Math.round(rect.width * dpr));
  overlayEl.height = Math.max(1, Math.round(rect.height * dpr));
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, rect.width, rect.height);
  const result = latestDetections;
  if (!result || !result.frame_size || !result.persons) return;
  const [sourceW, sourceH] = result.frame_size;
  const box = containBox(rect.width, rect.height, 16 / 9);
  const scaleX = box.w / sourceW;
  const scaleY = box.h / sourceH;
  ctx.lineWidth = 2;
  ctx.strokeStyle = "#e9c46a";
  ctx.fillStyle = "#e9c46a";
  for (const person of result.persons) {
    const [x1, y1, x2, y2] = person.box;
    ctx.strokeRect(box.x + x1 * scaleX, box.y + y1 * scaleY, (x2 - x1) * scaleX, (y2 - y1) * scaleY);
    for (const [start, end] of POSE_CONNECTIONS) {
      const a = person.keypoints[start];
      const b = person.keypoints[end];
      if (!a || !b || a[2] < 0.25 || b[2] < 0.25) continue;
      ctx.beginPath();
      ctx.moveTo(box.x + a[0] * scaleX, box.y + a[1] * scaleY);
      ctx.lineTo(box.x + b[0] * scaleX, box.y + b[1] * scaleY);
      ctx.stroke();
    }
    for (const point of person.keypoints) {
      if (point[2] < 0.25) continue;
      ctx.beginPath();
      ctx.arc(box.x + point[0] * scaleX, box.y + point[1] * scaleY, 3, 0, Math.PI * 2);
      ctx.fill();
    }
  }
}

function containBox(w, h, ratio) {
  const current = w / h;
  if (current > ratio) {
    const boxW = h * ratio;
    return { x: (w - boxW) / 2, y: 0, w: boxW, h };
  }
  const boxH = w / ratio;
  return { x: 0, y: (h - boxH) / 2, w, h: boxH };
}

async function refresh() {
  try {
    const status = await api("/api/status");
    renderRuntime(status);
    renderResources(status.resources);
    if (status.camera.error) {
      setStatus(status.camera.error);
    } else if (status.camera.hls_ready) {
      setStatus("Live preview ready");
    }
    const detections = await api("/api/detections");
    latestDetections = detections.result;
    renderMetrics(latestDetections);
    drawPoseModel(latestDetections);
    renderFaceCover(latestDetections);
    drawOverlay();
  } catch (err) {
    setStatus(err.message);
  }
}

function refreshSnapshot() {
  snapshotEl.src = `/api/snapshot?t=${Date.now()}`;
}

async function postButton(path, after) {
  try {
    await api(path, { method: "POST", body: "{}" });
    if (after) after();
    await refresh();
  } catch (err) {
    setStatus(err.message);
  }
}

document.getElementById("cameraStart").addEventListener("click", () => postButton("/api/camera/start", connectHls));
document.getElementById("cameraStop").addEventListener("click", () => postButton("/api/camera/stop"));
document.getElementById("visionStart").addEventListener("click", () => postButton("/api/vision/start"));
document.getElementById("visionStop").addEventListener("click", () => postButton("/api/vision/stop"));
document.getElementById("recordAudio").addEventListener("click", async () => {
  const seconds = Number(document.getElementById("audioSeconds").value || 2);
  try {
    const payload = await api("/api/audio/record", { method: "POST", body: JSON.stringify({ seconds }) });
    const result = payload.result;
    audioFields.status.textContent = "Recorded";
    audioFields.engine.textContent = result.engine;
    audioFields.duration.textContent = `${result.duration_s}s`;
    audioFields.rms.textContent = result.rms;
    audioFields.peak.textContent = result.peak;
    audioFields.dbfs.textContent = result.dbfs;
    audioFields.zcr.textContent = result.zero_crossing_rate;
    audioPlayerEl.src = `${result.audio_url}?t=${Date.now()}`;
    audioPlayerEl.hidden = false;
  } catch (err) {
    audioFields.status.textContent = err.message;
  }
});

document.getElementById("detectCry").addEventListener("click", async () => {
  const seconds = Number(document.getElementById("audioSeconds").value || 2);
  yamnetFields.status.textContent = "Recording...";
  try {
    const payload = await api("/api/audio/yamnet", { method: "POST", body: JSON.stringify({ seconds }) });
    const result = payload.result;
    yamnetFields.status.textContent = result.crying ? "Crying suspected" : "No crying detected";
    yamnetFields.cryScore.textContent = String(result.cry_score);
    yamnetFields.latency.textContent = `${result.elapsed_ms} ms`;
    yamnetFields.top.textContent = (result.top || [])
      .slice(0, 5)
      .map((item) => `${item.label} ${item.score}`)
      .join(", ");
    audioPlayerEl.src = `${result.audio_url}?t=${Date.now()}`;
    audioPlayerEl.hidden = false;
  } catch (err) {
    yamnetFields.status.textContent = err.message;
  }
});

window.addEventListener("resize", drawOverlay);
connectHls();
drawPoseModel(null);
renderFaceCover(null);
refresh();
refreshSnapshot();
setInterval(refresh, 1000);
setInterval(refreshSnapshot, 2000);
