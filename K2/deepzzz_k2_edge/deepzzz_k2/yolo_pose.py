from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import onnxruntime as ort


KP_CONNECTIONS = (
    (16, 14),
    (14, 12),
    (15, 13),
    (13, 11),
    (12, 11),
    (5, 7),
    (7, 9),
    (6, 8),
    (8, 10),
    (5, 6),
    (5, 11),
    (6, 12),
    (11, 13),
    (12, 14),
    (0, 1),
    (0, 2),
    (1, 3),
    (2, 4),
    (0, 5),
    (0, 6),
    (3, 5),
    (4, 6),
)


class YoloPoseEngine:
    def __init__(
        self,
        model_path: str | Path,
        conf_threshold: float = 0.25,
        iou_threshold: float = 0.45,
        provider: str = "CPUExecutionProvider",
    ) -> None:
        self.model_path = str(model_path)
        self.conf_threshold = conf_threshold
        self.iou_threshold = iou_threshold
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.session = ort.InferenceSession(self.model_path, sess_options=options, providers=[provider])
        self.input_name = self.session.get_inputs()[0].name
        self.output_names = [output.name for output in self.session.get_outputs()]
        shape = self.session.get_inputs()[0].shape
        self.input_shape = (int(shape[2]), int(shape[3]))
        self.provider = self.session.get_providers()[0]

    def infer(self, frame_bgr: np.ndarray) -> tuple[dict[str, Any], np.ndarray]:
        started = time.perf_counter()
        tensor, scale, pad = self._preprocess(frame_bgr)
        outputs = self.session.run(self.output_names, {self.input_name: tensor})
        detections = self._postprocess(frame_bgr.shape[:2], outputs[0], scale, pad)
        annotated = frame_bgr.copy()
        draw_pose(annotated, detections)
        elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
        return {
            "engine": "yolov8n_pose_cpu",
            "model": self.model_path,
            "provider": self.provider,
            "frame_size": [int(frame_bgr.shape[1]), int(frame_bgr.shape[0])],
            "elapsed_ms": elapsed_ms,
            "updated_at": time.time(),
            "model_active": True,
            "persons": detections_to_json(detections),
        }, annotated

    def _preprocess(self, image: np.ndarray) -> tuple[np.ndarray, float, tuple[float, float]]:
        height, width = image.shape[:2]
        input_h, input_w = self.input_shape
        scale = min(input_h / height, input_w / width)
        resized_w = int(round(width * scale))
        resized_h = int(round(height * scale))
        dw = (input_w - resized_w) / 2
        dh = (input_h - resized_h) / 2
        if (width, height) != (resized_w, resized_h):
            image = cv2.resize(image, (resized_w, resized_h), interpolation=cv2.INTER_LINEAR)
        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
        left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
        image = cv2.copyMakeBorder(image, top, bottom, left, right, cv2.BORDER_CONSTANT, value=(0, 0, 0))
        tensor = image.astype(np.float32) / 255.0
        tensor = np.transpose(tensor, (2, 0, 1))[None, ...]
        return tensor, scale, (dw, dh)

    def _postprocess(
        self,
        original_shape: tuple[int, int],
        output: np.ndarray,
        scale: float,
        pad: tuple[float, float],
    ) -> list[dict[str, Any]]:
        output = np.squeeze(output)
        if output.ndim != 2 or output.shape[0] < 56:
            return []
        input_h, input_w = self.input_shape
        orig_h, orig_w = original_shape
        dw, dh = pad

        cx = output[0, :]
        cy = output[1, :]
        w = output[2, :]
        h = output[3, :]
        scores = output[4, :]
        valid = scores >= self.conf_threshold
        if not np.any(valid):
            return []

        indices = np.where(valid)[0]
        x1 = np.maximum(0, cx[indices] - w[indices] / 2)
        y1 = np.maximum(0, cy[indices] - h[indices] / 2)
        x2 = np.minimum(input_w, cx[indices] + w[indices] / 2)
        y2 = np.minimum(input_h, cy[indices] + h[indices] / 2)
        scores = scores[indices]
        keypoints = output[5:, indices].T.reshape(-1, 17, 3)

        boxes = np.column_stack((x1, y1, x2, y2))
        keep = nms(boxes, scores, self.iou_threshold)
        detections: list[dict[str, Any]] = []
        for idx in keep:
            box = boxes[idx].copy()
            box[[0, 2]] = (box[[0, 2]] - dw) / scale
            box[[1, 3]] = (box[[1, 3]] - dh) / scale
            box[[0, 2]] = np.clip(box[[0, 2]], 0, orig_w - 1)
            box[[1, 3]] = np.clip(box[[1, 3]], 0, orig_h - 1)

            kps = keypoints[idx].copy()
            kps[:, 0] = np.clip((kps[:, 0] - dw) / scale, 0, orig_w - 1)
            kps[:, 1] = np.clip((kps[:, 1] - dh) / scale, 0, orig_h - 1)
            detections.append(
                {
                    "box": box.astype(float).tolist(),
                    "score": float(scores[idx]),
                    "keypoints": kps.astype(float).tolist(),
                }
            )
        return detections


def nms(boxes: np.ndarray, scores: np.ndarray, threshold: float) -> list[int]:
    order = np.argsort(scores)[::-1]
    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        ious = calculate_iou(boxes[i], boxes[order[1:]])
        order = order[1:][ious < threshold]
    return keep


def calculate_iou(box: np.ndarray, boxes: np.ndarray) -> np.ndarray:
    x1 = np.maximum(box[0], boxes[:, 0])
    y1 = np.maximum(box[1], boxes[:, 1])
    x2 = np.minimum(box[2], boxes[:, 2])
    y2 = np.minimum(box[3], boxes[:, 3])
    inter = np.maximum(0, x2 - x1) * np.maximum(0, y2 - y1)
    area1 = max(0.0, (box[2] - box[0]) * (box[3] - box[1]))
    area2 = np.maximum(0, boxes[:, 2] - boxes[:, 0]) * np.maximum(0, boxes[:, 3] - boxes[:, 1])
    return inter / np.maximum(1e-6, area1 + area2 - inter)


def draw_pose(image: np.ndarray, detections: list[dict[str, Any]], confidence_threshold: float = 0.25) -> None:
    for det in detections:
        x1, y1, x2, y2 = [int(v) for v in det["box"]]
        cv2.rectangle(image, (x1, y1), (x2, y2), (42, 157, 143), 2)
        keypoints = det["keypoints"]
        for x, y, conf in keypoints:
            if conf >= confidence_threshold:
                cv2.circle(image, (int(x), int(y)), 3, (233, 196, 106), -1)
        for start, end in KP_CONNECTIONS:
            sx, sy, sv = keypoints[start]
            ex, ey, ev = keypoints[end]
            if sv >= confidence_threshold and ev >= confidence_threshold:
                cv2.line(image, (int(sx), int(sy)), (int(ex), int(ey)), (233, 196, 106), 2)
        cv2.putText(
            image,
            f"person {float(det['score']):.2f}",
            (x1, max(16, y1 - 6)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (245, 245, 240),
            1,
            cv2.LINE_AA,
        )


def detections_to_json(detections: list[dict[str, Any]]) -> list[dict[str, Any]]:
    people = []
    for det in detections:
        people.append(
            {
                "box": [round(float(v), 2) for v in det["box"]],
                "score": round(float(det["score"]), 4),
                "keypoints": [
                    [round(float(x), 2), round(float(y), 2), round(float(conf), 4)]
                    for x, y, conf in det["keypoints"]
                ],
            }
        )
    return people
