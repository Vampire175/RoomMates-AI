import tkinter as tk
from tkinter import ttk, messagebox
import cv2
import mediapipe as mp
import numpy as np
import time
import os
import threading
import platform
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core import base_options
import serial
from serial.tools import list_ports
from PIL import Image, ImageTk


# =========================================================
# BLUETOOTH SELECT DIALOG
# =========================================================

class BluetoothSelectDialog:
    """Scans available serial/BT ports and lets user pick one."""

    def __init__(self, parent, on_connect):
        self.on_connect = on_connect

        self.win = tk.Toplevel(parent)
        self.win.title("🔵  Select Bluetooth Device")
        self.win.geometry("480x360")
        self.win.configure(bg="#1e1e2e")
        self.win.resizable(False, False)
        self.win.grab_set()  # modal

        tk.Label(self.win, text="Available Devices",
                 font=("Segoe UI", 14, "bold"),
                 fg="#89b4fa", bg="#1e1e2e").pack(pady=(14, 6))

        # ── Device list ──
        frame = tk.Frame(self.win, bg="#1e1e2e")
        frame.pack(fill=tk.BOTH, expand=True, padx=20)

        scrollbar = tk.Scrollbar(frame)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        self.listbox = tk.Listbox(
            frame, bg="#313244", fg="#cdd6f4",
            font=("Segoe UI", 10), selectbackground="#89b4fa",
            relief=tk.FLAT, yscrollcommand=scrollbar.set,
            activestyle="none", height=10
        )
        self.listbox.pack(fill=tk.BOTH, expand=True)
        scrollbar.config(command=self.listbox.yview)

        self.ports = []  # parallel list of port strings
        self._scan()

        # ── Buttons ──
        btn_row = tk.Frame(self.win, bg="#1e1e2e")
        btn_row.pack(pady=10)

        tk.Button(btn_row, text="🔄  Refresh",
                  command=self._scan,
                  bg="#313244", fg="#cdd6f4",
                  font=("Segoe UI", 10, "bold"), relief=tk.FLAT, padx=10
                  ).pack(side=tk.LEFT, padx=8)

        tk.Button(btn_row, text="🔵  Connect",
                  command=self._connect,
                  bg="#89b4fa", fg="#1e1e2e",
                  font=("Segoe UI", 10, "bold"), relief=tk.FLAT, padx=10
                  ).pack(side=tk.LEFT, padx=8)

        tk.Button(btn_row, text="✖  Cancel",
                  command=self.win.destroy,
                  bg="#f38ba8", fg="#1e1e2e",
                  font=("Segoe UI", 10, "bold"), relief=tk.FLAT, padx=10
                  ).pack(side=tk.LEFT, padx=8)

    def _scan(self):
        self.listbox.delete(0, tk.END)
        self.ports = []

        all_ports = sorted(list_ports.comports(), key=lambda x: x.device)
        if not all_ports:
            self.listbox.insert(tk.END, "  No devices found.")
            return

        for p in all_ports:
            desc  = p.description or "Unknown device"
            is_bt = any(k in desc.lower()
                        for k in ("bluetooth", "bt", "rfcomm", "bthmodem"))
            tag     = "🔵" if is_bt else "🔌"
            display = f"  {tag}  {p.device:<12}  {desc}"
            self.listbox.insert(tk.END, display)
            self.ports.append(p.device)

    def _connect(self):
        sel = self.listbox.curselection()
        if not sel:
            messagebox.showwarning("No selection",
                                   "Please select a device first.",
                                   parent=self.win)
            return
        idx = sel[0]
        if idx >= len(self.ports):
            return
        port = self.ports[idx]
        self.win.destroy()
        self.on_connect(port)


# =========================================================
# FACE REGISTRATION WINDOW
# =========================================================

class FaceRegistrationWindow:
    """
    Separate Toplevel window for registering new faces.

    Flow:
        1. User types a name.
        2. User clicks "Start Capture" – camera saves cropped
           grayscale face ROIs until CAPTURE_TARGET images collected.
        3. On close, parent callback triggers model retraining.
    """

    CAPTURE_TARGET = 40   # images collected per person
    CONF_THRESHOLD = 80   # LBPH confidence – lower = better match

    def __init__(self, parent, faces_dir: str, on_close_callback):
        self.parent            = parent
        self.faces_dir         = faces_dir
        self.on_close_callback = on_close_callback

        self.cap           = None
        self.is_running    = False
        self.capturing     = False
        self.capture_count = 0
        self.current_name  = ""

        self.face_cascade = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        )

        # ── Window ──
        self.win = tk.Toplevel(parent)
        self.win.title("👤  Face Registration")
        self.win.geometry("720x640")
        self.win.configure(bg="#1e1e2e")
        self.win.resizable(False, False)
        self.win.protocol("WM_DELETE_WINDOW", self._close)

        self._build_ui()
        self._start_camera()

    # ----------------------------------------------------------
    # UI
    # ----------------------------------------------------------

    def _build_ui(self):
        tk.Label(self.win, text="Face Registration",
                 font=("Segoe UI", 16, "bold"),
                 fg="#cba6f7", bg="#1e1e2e").pack(pady=(14, 6))

        # ── Name + buttons ──
        top = tk.Frame(self.win, bg="#1e1e2e")
        top.pack(fill=tk.X, padx=20, pady=6)

        tk.Label(top, text="Name:", fg="#cdd6f4", bg="#1e1e2e",
                 font=("Segoe UI", 11)).pack(side=tk.LEFT)

        self.name_var = tk.StringVar()
        ttk.Entry(top, textvariable=self.name_var, width=18,
                  font=("Segoe UI", 11)).pack(side=tk.LEFT, padx=8)

        self.capture_btn = tk.Button(
            top, text="📸  Start Capture",
            command=self._start_capture,
            bg="#a6e3a1", fg="#1e1e2e",
            font=("Segoe UI", 11, "bold"), relief=tk.FLAT, padx=8
        )
        self.capture_btn.pack(side=tk.LEFT, padx=4)

        tk.Button(top, text="🗑  Clear All",
                  command=self._clear_all,
                  bg="#f38ba8", fg="#1e1e2e",
                  font=("Segoe UI", 11, "bold"), relief=tk.FLAT, padx=8
                  ).pack(side=tk.LEFT, padx=4)

        # ── Progress ──
        prog_frame = tk.Frame(self.win, bg="#1e1e2e")
        prog_frame.pack(fill=tk.X, padx=20, pady=2)

        self.progress_var = tk.StringVar(
            value="Ready – enter a name and click Start Capture.")
        tk.Label(prog_frame, textvariable=self.progress_var,
                 fg="#a6e3a1", bg="#1e1e2e",
                 font=("Segoe UI", 10)).pack(anchor=tk.W)

        self.progress_bar = ttk.Progressbar(self.win, length=680,
                                            mode="determinate")
        self.progress_bar.pack(padx=20, pady=(2, 6))

        # ── Registered faces list ──
        list_frame = tk.Frame(self.win, bg="#1e1e2e")
        list_frame.pack(fill=tk.X, padx=20, pady=(0, 6))

        tk.Label(list_frame, text="Registered Faces:",
                 fg="#cdd6f4", bg="#1e1e2e",
                 font=("Segoe UI", 10, "bold")).pack(anchor=tk.W)

        self.faces_lb = tk.Listbox(
            list_frame, bg="#313244", fg="#cdd6f4",
            font=("Segoe UI", 10), height=3,
            selectbackground="#cba6f7", relief=tk.FLAT
        )
        self.faces_lb.pack(fill=tk.X)
        self._refresh_list()

        # ── Live feed ──
        self.video_label = tk.Label(self.win, bg="black")
        self.video_label.pack(fill=tk.BOTH, expand=True, padx=20, pady=(4, 14))

    # ----------------------------------------------------------
    # Camera
    # ----------------------------------------------------------

    def _start_camera(self):
        backend = cv2.CAP_DSHOW if platform.system() == "Windows" else cv2.CAP_V4L2
        self.cap = cv2.VideoCapture(0, backend)
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        self.is_running = True
        threading.Thread(target=self._loop, daemon=True).start()

    # ----------------------------------------------------------
    # Actions
    # ----------------------------------------------------------

    def _start_capture(self):
        name = self.name_var.get().strip()
        if not name:
            messagebox.showwarning("Name required",
                                   "Enter a name before capturing.")
            return
        self.current_name  = name
        self.capture_count = 0
        self.capturing     = True
        os.makedirs(os.path.join(self.faces_dir, name), exist_ok=True)
        self.progress_bar["value"] = 0
        self.progress_var.set(f"Capturing for: {name} …")
        self.capture_btn.config(state=tk.DISABLED)

    def _clear_all(self):
        if not messagebox.askyesno("Confirm",
                                   "Delete all registered face data?"):
            return
        import shutil
        if os.path.exists(self.faces_dir):
            shutil.rmtree(self.faces_dir)
        os.makedirs(self.faces_dir, exist_ok=True)
        self._refresh_list()
        self.progress_var.set("All faces cleared.")

    def _refresh_list(self):
        self.faces_lb.delete(0, tk.END)
        if not os.path.exists(self.faces_dir):
            return
        for n in sorted(os.listdir(self.faces_dir)):
            d = os.path.join(self.faces_dir, n)
            if os.path.isdir(d):
                cnt = len(os.listdir(d))
                self.faces_lb.insert(tk.END, f"  {n}  –  {cnt} images")

    # ----------------------------------------------------------
    # Frame loop
    # ----------------------------------------------------------

    def _loop(self):
        while self.is_running:
            ret, frame = self.cap.read()
            if not ret:
                time.sleep(0.02)
                continue

            frame = cv2.flip(frame, 1)
            gray  = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            faces = self.face_cascade.detectMultiScale(
                gray, scaleFactor=1.1, minNeighbors=5, minSize=(80, 80)
            )

            for (x, y, w, h) in faces:
                color = (0, 220, 120) if self.capturing else (160, 80, 220)
                cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)

                if self.capturing and self.capture_count < self.CAPTURE_TARGET:
                    roi      = cv2.resize(gray[y:y+h, x:x+w], (100, 100))
                    img_path = os.path.join(
                        self.faces_dir, self.current_name,
                        f"{self.capture_count:04d}.jpg"
                    )
                    cv2.imwrite(img_path, roi)
                    self.capture_count += 1
                    pct = self.capture_count / self.CAPTURE_TARGET * 100

                    self.win.after(0, lambda p=pct:
                                   self.progress_bar.configure(value=p))
                    self.win.after(
                        0, self.progress_var.set,
                        f"Capturing {self.current_name}: "
                        f"{self.capture_count}/{self.CAPTURE_TARGET}"
                    )

                    if self.capture_count >= self.CAPTURE_TARGET:
                        self.capturing = False
                        self.win.after(0, lambda:
                                       self.capture_btn.config(state=tk.NORMAL))
                        self.win.after(0, self.progress_var.set,
                                       f"✅  {self.current_name} registered!")
                        self.win.after(0, self._refresh_list)
                        self.win.after(0, lambda: messagebox.showinfo(
                            "Done",
                            f"'{self.current_name}' registered with "
                            f"{self.CAPTURE_TARGET} face images.\n\n"
                            "Close this window to retrain the model."
                        ))

            # On-frame progress bar overlay
            if self.capturing:
                bar_w = int(frame.shape[1] *
                            self.capture_count / self.CAPTURE_TARGET)
                cv2.rectangle(frame,
                              (0, frame.shape[0] - 8),
                              (bar_w, frame.shape[0]),
                              (0, 220, 120), -1)

            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            img = ImageTk.PhotoImage(Image.fromarray(rgb))
            self.video_label.configure(image=img)
            self.video_label.image = img
            time.sleep(0.04)

    # ----------------------------------------------------------
    # Close
    # ----------------------------------------------------------

    def _close(self):
        self.is_running = False
        if self.cap:
            self.cap.release()
        self.win.destroy()
        if self.on_close_callback:
            self.on_close_callback()


# =========================================================
# MAIN APP
# =========================================================

class GestureControlApp:

    CONF_THRESHOLD  = 80    # LBPH: lower = better match; above = "Unknown"
    FACE_SKIP       = 3     # run face recognition every N frames
    SESSION_TIMEOUT = 10.0  # seconds before session auto-expires

    def __init__(self, root):
        self.root = root
        self.root.title("Gesture Control")
        self.root.geometry("1200x850")
        self.root.configure(bg="#2b2b2b")

        # ── Core state ──
        self.esp32      = None
        self.cap        = None
        self.is_running = False

        # ── Paths ──
        self.SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
        self.MODEL_PATH = os.path.join(self.SCRIPT_DIR, "gesture_recognizer.task")
        self.FACES_DIR  = os.path.join(self.SCRIPT_DIR, "registered_faces")
        os.makedirs(self.FACES_DIR, exist_ok=True)

        # ── Gesture ──
        self.GESTURE_COOLDOWN  = 1.0
        self.last_gesture_time = 0
        self.gesture_bytes = {
            "start":     [1, 1, 1, 1, 1],
            "index":     [0, 1, 1, 1, 1],
            "middle":    [0, 0, 1, 1, 1],
            "ring":      [0, 0, 0, 1, 1],
            "pinky":     [0, 0, 0, 0, 1],
            "openpalm":  [0, 0, 0, 0, 0],
            "closepalm": [1, 1, 1, 1, 1],
        }

        # ── Session (activated by "start" gesture, expires after 10s) ──
        self.session_active     = False   # True only after "start" gesture
        self.session_start_time = 0.0     # timestamp of last "start" gesture

        # ── Face state bools ──
        self.face_detected = False   # True if any face found in frame
        self.face_unknown  = False   # True if detected face not recognised
        self.isVerified    = False   # True if known face present (ESP32 gate)

        # ── MediaPipe ──
        self.mp_hands           = None
        self.mp_drawing         = mp.solutions.drawing_utils
        self.mp_styles          = mp.solutions.drawing_styles
        self.gesture_recognizer = None
        self.gesture_lock       = threading.Lock()
        self.latest_gesture     = None

        # ── Face recognition ──
        self.face_cascade       = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        )
        self.face_recognizer    = cv2.face.LBPHFaceRecognizer_create()
        self.label_map          = {}   # int → name
        self.face_model_trained = False
        self.face_lock          = threading.Lock()
        self.recognized_faces   = []   # [(x,y,w,h, name, confidence), …]
        self._face_skip_counter = 0

        self._train_face_model()

        # ── UI ──
        self._build_ui()

    # =========================================================
    # FACE MODEL TRAINING
    # =========================================================

    def _train_face_model(self):
        """Scan FACES_DIR and build an LBPH model from stored images."""
        images, labels = [], []
        self.label_map = {}
        label_id       = 0

        for person in sorted(os.listdir(self.FACES_DIR)):
            person_dir = os.path.join(self.FACES_DIR, person)
            if not os.path.isdir(person_dir):
                continue
            added = 0
            for fname in os.listdir(person_dir):
                img = cv2.imread(os.path.join(person_dir, fname),
                                 cv2.IMREAD_GRAYSCALE)
                if img is not None:
                    images.append(cv2.resize(img, (100, 100)))
                    labels.append(label_id)
                    added += 1
            if added:
                self.label_map[label_id] = person
                label_id += 1

        if len(images) >= 2:
            self.face_recognizer.train(images, np.array(labels))
            self.face_model_trained = True
            print(f"[FaceModel] Trained: {label_id} people, "
                  f"{len(images)} images.")
        else:
            self.face_model_trained = False
            print("[FaceModel] Not trained – register at least one person.")

    # =========================================================
    # UI
    # =========================================================

    def _build_ui(self):
        main = tk.Frame(self.root, bg="#2b2b2b")
        main.pack(fill=tk.BOTH, expand=True, padx=25, pady=20)

        # Title
        ttk.Label(main, text="RoomMates AI",
                  font=("Segoe UI", 18, "bold"),
                  foreground="#00ff88").pack(pady=(0, 12))

        # ── Top control bar ──
        ctrl = tk.Frame(main, bg="#3a3a3a", pady=8)
        ctrl.pack(fill=tk.X, pady=(0, 6))

        self._make_btn(ctrl, "🔵 Connect Bluetooth",
                       self._open_bt_dialog,     "#89b4fa", "#1e1e2e")
        self._make_btn(ctrl, "▶ Start Camera",
                       self._toggle_camera,       "#00aa44", "white")
        self._make_btn(ctrl, "👤 Register Face",
                       self._open_registration,   "#6c8ebf", "white")
        self._make_btn(ctrl, "🔄 Retrain Model",
                       self._retrain,             "#fab387", "black")

        # ── Status bar ──
        stat = tk.Frame(main, bg="#252535", pady=6)
        stat.pack(fill=tk.X, pady=(0, 8))

        self.bt_status      = tk.StringVar(value="Bluetooth: —")
        self.gesture_status = tk.StringVar(value="Gesture: —")
        self.face_status    = tk.StringVar(value="Face: —")
        self.model_status   = tk.StringVar(
            value=f"Model: "
                  f"{'✅ Trained' if self.face_model_trained else '⚠ Untrained'}"
        )
        self.session_status = tk.StringVar(value="Session: inactive 🔴")

        for var, color in [
            (self.bt_status,      "#89b4fa"),
            (self.gesture_status, "#00ff88"),
            (self.face_status,    "#cba6f7"),
            (self.model_status,   "#fab387"),
            (self.session_status, "#a6e3a1"),
        ]:
            tk.Label(stat, textvariable=var, fg=color, bg="#252535",
                     font=("Segoe UI", 10, "bold")).pack(side=tk.LEFT,
                                                         padx=14)

        # ── Video feed ──
        self.video_label = tk.Label(main, bg="black")
        self.video_label.pack(fill=tk.BOTH, expand=True)

    @staticmethod
    def _make_btn(parent, text, cmd, bg, fg="white"):
        tk.Button(
            parent, text=text, command=cmd,
            bg=bg, fg=fg,
            font=("Segoe UI", 10, "bold"),
            relief=tk.FLAT, padx=10, pady=4
        ).pack(side=tk.LEFT, padx=6)

    # =========================================================
    # BLUETOOTH
    # =========================================================

    def _open_bt_dialog(self):
        BluetoothSelectDialog(self.root, self._connect_to_port)

    def _connect_to_port(self, port: str):
        try:
            self.esp32 = serial.Serial(port=port, baudrate=9600, timeout=1)
            self.bt_status.set(f"Bluetooth: ✅ {port}")
            messagebox.showinfo("Connected", f"✅  ESP32 connected on {port}")
        except Exception as exc:
            self.bt_status.set("Bluetooth: ❌ Failed")
            messagebox.showerror("Connection Error", str(exc))

    # =========================================================
    # REGISTRATION
    # =========================================================

    def _open_registration(self):
        FaceRegistrationWindow(self.root, self.FACES_DIR,
                               self._on_registration_closed)

    def _on_registration_closed(self):
        self._retrain()

    def _retrain(self):
        self._train_face_model()
        status = "✅ Trained" if self.face_model_trained else "⚠ Untrained"
        self.model_status.set(f"Model: {status}")

    # =========================================================
    # CAMERA
    # =========================================================

    def _toggle_camera(self):
        if not self.is_running:
            self._start_camera()
        else:
            self._stop_camera()

    def _start_camera(self):
        backend = cv2.CAP_DSHOW if platform.system() == "Windows" else cv2.CAP_V4L2
        self.cap = cv2.VideoCapture(0, backend)
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH,  640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        self.cap.set(cv2.CAP_PROP_FPS, 15)
        self.is_running = True
        self._init_mediapipe()
        threading.Thread(target=self._frame_loop, daemon=True).start()

    def _stop_camera(self):
        self.is_running = False
        if self.cap:
            self.cap.release()

    # =========================================================
    # MEDIAPIPE
    # =========================================================

    def _init_mediapipe(self):
        self.mp_hands = mp.solutions.hands.Hands(
            static_image_mode=False, max_num_hands=1,
            min_detection_confidence=0.6, min_tracking_confidence=0.6
        )
        with open(self.MODEL_PATH, "rb") as f:
            model_bytes = f.read()

        opts = vision.GestureRecognizerOptions(
            base_options=base_options.BaseOptions(
                model_asset_buffer=model_bytes),
            running_mode=vision.RunningMode.LIVE_STREAM,
            result_callback=self._gesture_callback,
            num_hands=1
        )
        self.gesture_recognizer = \
            vision.GestureRecognizer.create_from_options(opts)

    def _gesture_callback(self, result, _img, _ts):
        with self.gesture_lock:
            self.latest_gesture = (result.gestures[0][0]
                                   if result.gestures else None)

    # =========================================================
    # FACE RECOGNITION
    # =========================================================

    def _recognize_faces(self, frame):
        """Return list of (x, y, w, h, name, confidence) and update bools."""
        if not self.face_model_trained:
            self.face_detected = False
            self.face_unknown  = False
            self.isVerified    = False
            return []

        gray  = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        rects = self.face_cascade.detectMultiScale(
            gray, scaleFactor=1.1, minNeighbors=5, minSize=(70, 70)
        )
        results = []
        for (x, y, w, h) in rects:
            roi = cv2.resize(gray[y:y+h, x:x+w], (100, 100))
            try:
                lid, conf = self.face_recognizer.predict(roi)
                name = self.label_map.get(lid, "Unknown")
            except Exception:
                name, conf = "Unknown", 999
            results.append((x, y, w, h, name, float(conf)))

        # ── Update state bools ──
        self.face_detected = len(results) > 0
        self.face_unknown  = any(f[5] >= self.CONF_THRESHOLD for f in results)
        self.isVerified    = self.face_detected and not self.face_unknown

        return results

    def _draw_faces(self, frame, faces):
        for (x, y, w, h, name, conf) in faces:
            known = conf < self.CONF_THRESHOLD
            color = (0, 230, 130) if known else (80, 80, 230)
            label = f"{name if known else 'Unknown'}  ({conf:.0f})"

            cv2.rectangle(frame, (x, y), (x + w, y + h), color, 2)

            (tw, th), _ = cv2.getTextSize(
                label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 1)
            cv2.rectangle(frame,
                          (x, y - th - 10), (x + tw + 6, y), color, -1)
            cv2.putText(frame, label, (x + 3, y - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                        (0, 0, 0) if known else (255, 255, 255),
                        1, cv2.LINE_AA)
        return frame

    # =========================================================
    # SESSION HELPERS
    # =========================================================

    def _session_expired(self, now: float) -> bool:
        """Return True if session window has passed SESSION_TIMEOUT."""
        return (now - self.session_start_time) > self.SESSION_TIMEOUT

    def _expire_session(self):
        """Mark session inactive and update UI."""
        self.session_active = False
        self.root.after(0, self.session_status.set, "Session: inactive 🔴")
        print("[Session] Expired")

    def _activate_session(self, now: float):
        """Start or refresh the 10-second session window."""
        self.session_active     = True
        self.session_start_time = now
        remaining = int(self.SESSION_TIMEOUT)
        self.root.after(0, self.session_status.set,
                        f"Session: active 🟢 ({remaining}s)")
        print("[Session] Started / Refreshed")

    # =========================================================
    # MAIN FRAME LOOP
    # =========================================================

    def _frame_loop(self):
        timestamp = 0

        while self.is_running:
            ret, frame = self.cap.read()
            if not ret:
                time.sleep(0.02)
                continue

            frame = cv2.flip(frame, 1)
            rgb   = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

            # ── Hand skeleton ──
            hand_res = self.mp_hands.process(rgb)
            if hand_res.multi_hand_landmarks:
                for hl in hand_res.multi_hand_landmarks:
                    self.mp_drawing.draw_landmarks(
                        frame, hl,
                        mp.solutions.hands.HAND_CONNECTIONS,
                        self.mp_styles.get_default_hand_landmarks_style(),
                        self.mp_styles.get_default_hand_connections_style()
                    )

            # ── Gesture recognition ──
            timestamp += 1
            mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            self.gesture_recognizer.recognize_async(mp_img, timestamp)

            with self.gesture_lock:
                g = self.latest_gesture

            if g:
                gname = g.category_name
                self.root.after(0, self.gesture_status.set,
                                f"Gesture: {gname}")

                if self.esp32:
                    now = time.time()

                    # ── Check session expiry ──
                    if self.session_active and self._session_expired(now):
                        self._expire_session()

                    # ── Update session countdown on status bar ──
                    if self.session_active:
                        remaining = max(0, int(self.SESSION_TIMEOUT -
                                               (now - self.session_start_time)))
                        self.root.after(0, self.session_status.set,
                                        f"Session: active 🟢 ({remaining}s)")

                    if now - self.last_gesture_time > self.GESTURE_COOLDOWN:
                        if gname in self.gesture_bytes and self.isVerified:

                            if gname == "start":
                                # Always activate/refresh session on "start"
                                self._activate_session(now)

                            # Only write bytes when session is active
                            if self.session_active:
                                self.esp32.write(
                                    bytes(self.gesture_bytes[gname]))
                                self.last_gesture_time = now
                                print(f"[ESP32] Sent: {gname}")
                            else:
                                print(f"[ESP32] Blocked – no active session "
                                      f"(show 'start' gesture first)")
            else:
                self.root.after(0, self.gesture_status.set, "Gesture: —")

                # Still tick the countdown even when no gesture visible
                if self.session_active:
                    now = time.time()
                    if self._session_expired(now):
                        self._expire_session()
                    else:
                        remaining = max(0, int(self.SESSION_TIMEOUT -
                                               (now - self.session_start_time)))
                        self.root.after(0, self.session_status.set,
                                        f"Session: active 🟢 ({remaining}s)")

            # ── Face recognition (every FACE_SKIP frames) ──
            self._face_skip_counter += 1
            if self._face_skip_counter >= self.FACE_SKIP:
                self._face_skip_counter = 0
                faces = self._recognize_faces(frame)
                with self.face_lock:
                    self.recognized_faces = faces

                if faces:
                    names = [
                        f[4] if f[5] < self.CONF_THRESHOLD else "Unknown"
                        for f in faces
                    ]
                    self.root.after(0, self.face_status.set,
                                    f"Face: {', '.join(names)}")
                else:
                    self.root.after(0, self.face_status.set,
                                    "Face: none detected")

            # ── Draw face boxes ──
            with self.face_lock:
                faces_copy = list(self.recognized_faces)
            frame = self._draw_faces(frame, faces_copy)

            # ── Overlay session timer on video frame ──
            if self.session_active:
                now       = time.time()
                elapsed   = now - self.session_start_time
                remaining = max(0.0, self.SESSION_TIMEOUT - elapsed)
                bar_w     = int(frame.shape[1] * remaining / self.SESSION_TIMEOUT)
                # Green bar shrinks as session time runs out
                cv2.rectangle(frame,
                              (0, 0), (bar_w, 6),
                              (0, 230, 130), -1)
                cv2.putText(frame,
                            f"Session: {remaining:.1f}s",
                            (8, 22),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                            (0, 230, 130), 2, cv2.LINE_AA)
            else:
                cv2.putText(frame,
                            "Show 'start' gesture to activate",
                            (8, 22),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                            (80, 80, 230), 2, cv2.LINE_AA)

            # ── Display ──
            rgb_out = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            photo   = ImageTk.PhotoImage(Image.fromarray(rgb_out))
            self.video_label.configure(image=photo)
            self.video_label.image = photo


# =========================================================
# ENTRY POINT
# =========================================================

def main():
    root = tk.Tk()
    GestureControlApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
