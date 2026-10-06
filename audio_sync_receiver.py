import socket
import sounddevice as sd
import numpy as np
import threading
import time
import json
import av
import os
from collections import deque

UDP_PORT = 5005
SMB_PREFIX = "smb://192.168.0.105/"
LOCAL_PREFIX = "F:\\"
SAMPLE_RATE = 48000
CHANNELS = 2
BLOCK_SIZE = 1024
MAX_BUFFER_SAMPLES = SAMPLE_RATE        # 1 сек буфера
SEEK_THRESHOLD_MS = 2000                # если отстали больше 2 сек — ищем

state_lock = threading.Lock()
current_file = None
tv_position_ms = 0
tv_state = "IDLE"

playback_lock = threading.Lock()
decoder_start_pos_ms = 0
samples_output = 0

audio_buffer = deque()
audio_buffer_count = 0
audio_buffer_lock = threading.Lock()

file_changed = threading.Event()
resync_requested = threading.Event()
resync_target_ms = 0
stop_event = threading.Event()


def smb_to_local(smb):
    if not smb: return None
    if smb.startswith(SMB_PREFIX):
        return LOCAL_PREFIX + smb[len(SMB_PREFIX):].replace('/', '\\')
    return None


def buffer_push(arr):
    global audio_buffer_count
    with audio_buffer_lock:
        audio_buffer.append(arr)
        audio_buffer_count += arr.shape[0]


def buffer_clear():
    global audio_buffer_count
    with audio_buffer_lock:
        audio_buffer.clear()
        audio_buffer_count = 0


def buffer_count():
    with audio_buffer_lock:
        return audio_buffer_count


def buffer_pull(n):
    global audio_buffer_count
    result, got = [], 0
    with audio_buffer_lock:
        while got < n and audio_buffer:
            block = audio_buffer[0]
            if block.shape[0] <= n - got:
                result.append(block); got += block.shape[0]
                audio_buffer.popleft()
            else:
                take = n - got
                result.append(block[:take])
                audio_buffer[0] = block[take:]
                got += take
        audio_buffer_count -= got
    if not result: return None
    return result[0] if len(result) == 1 else np.concatenate(result, axis=0)


def get_playback_pos_ms():
    with playback_lock:
        return decoder_start_pos_ms + int(samples_output * 1000 / SAMPLE_RATE)


def udp_thread():
    global current_file, tv_position_ms, tv_state, resync_target_ms
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('0.0.0.0', UDP_PORT))
    sock.settimeout(0.5)
    print(f"[UDP] Слушаю порт {UDP_PORT}")
    while not stop_event.is_set():
        try:
            data, _ = sock.recvfrom(65536)
            msg = json.loads(data.decode('utf-8'))
            local = smb_to_local(msg.get('file', ''))
            pos = msg.get('pos', 0)
            st = msg.get('state', 'IDLE')

            with state_lock:
                prev_file = current_file
                prev_state = tv_state
                current_file = local
                tv_position_ms = pos
                tv_state = st

            if local != prev_file:
                buffer_clear()
                file_changed.set()
                resync_requested.clear()
                print(f"[SYNC] file: {local} @ {pos}ms {st}")
                continue

            if prev_state != st:
                print(f"[SYNC] state: {prev_state} -> {st} @ {pos}ms")
                if st != "PLAY":
                    buffer_clear()

            if st == "PLAY":
                actual = get_playback_pos_ms()
                drift = pos - actual
                if abs(drift) > SEEK_THRESHOLD_MS:
                    print(f"[SYNC] drift {drift}ms, resync to {pos}ms")
                    resync_target_ms = pos
                    resync_requested.set()
                    buffer_clear()
        except socket.timeout:
            continue
        except Exception as e:
            print(f"[UDP] error: {e}")
    sock.close()


def do_seek_to_target(container, stream, resampler, target_ms):
    """Seek в контейнере + пропуск кадров до pts >= target_ms.
    Возвращает (decoder, first_arr, first_pts_ms)."""
    container.seek(target_ms * 1000, backward=True)
    decoder = container.decode(stream)
    first_pts_ms = target_ms
    first_arr = None
    while True:
        frame = next(decoder)
        if frame.pts is None:
            continue
        pts_ms = int(frame.pts * stream.time_base * 1000)
        if pts_ms >= target_ms:
            first_pts_ms = pts_ms
            resampled = resampler.resample(frame)
            if not isinstance(resampled, list):
                resampled = [resampled] if resampled else []
            if resampled:
                arr = resampled[0].to_ndarray()
                if arr.ndim == 2: arr = arr.T
                first_arr = np.clip(arr, -1.0, 1.0).astype(np.float32)
            break
    return decoder, first_arr, first_pts_ms


def decoder_thread():
    global decoder_start_pos_ms, samples_output, resync_target_ms

    container = None
    stream = None
    resampler = None
    decoder = None
    current_path = None

    while not stop_event.is_set():
        with state_lock:
            path = current_file
            target_ms = tv_position_ms
            st = tv_state

        # === Файл сменился или только что открылся ===
        if path != current_path:
            if container:
                try: container.close()
                except: pass
                container = None
            current_path = None
            decoder = None

            if not path or not os.path.exists(path):
                time.sleep(0.3)
                continue

            print(f"[DEC] opening {path} @ {target_ms}ms")
            try:
                container = av.open(path)
                stream = container.streams.audio[0]
                resampler = av.AudioResampler(format='fltp', layout='stereo', rate=SAMPLE_RATE)
                decoder, first_arr, first_pts_ms = do_seek_to_target(
                    container, stream, resampler, target_ms)

                buffer_clear()
                with playback_lock:
                    decoder_start_pos_ms = first_pts_ms
                    samples_output = 0
                if first_arr is not None:
                    buffer_push(first_arr)

                current_path = path
                file_changed.clear()
                print(f"[DEC] ready @ {first_pts_ms}ms")
            except Exception as e:
                print(f"[DEC] open error: {e}")
                if container:
                    try: container.close()
                    except: pass
                    container = None
                current_path = None
                time.sleep(0.5)
            continue

        # === Запрос на ресинк (перемотка на ТВ) ===
        if resync_requested.is_set():
            resync_requested.clear()
            target = resync_target_ms
            print(f"[DEC] in-place seek to {target}ms")
            try:
                decoder, first_arr, first_pts_ms = do_seek_to_target(
                    container, stream, resampler, target)
                buffer_clear()
                with playback_lock:
                    decoder_start_pos_ms = first_pts_ms
                    samples_output = 0
                if first_arr is not None:
                    buffer_push(first_arr)
                print(f"[DEC] resynced @ {first_pts_ms}ms")
            except Exception as e:
                print(f"[DEC] resync error: {e}, full reopen")
                if container:
                    try: container.close()
                    except: pass
                    container = None
                current_path = None
            continue

        # === Пауза ===
        if st != "PLAY":
            time.sleep(0.05)
            continue

        # === Ждём свободное место в буфере ===
        while buffer_count() >= MAX_BUFFER_SAMPLES and not stop_event.is_set():
            time.sleep(0.02)
            with state_lock:
                if tv_state != "PLAY": break
                if current_file != current_path: break
            if resync_requested.is_set(): break

        # === Декодируем один кадр ===
        try:
            frame = next(decoder)
        except StopIteration:
            print(f"[DEC] EOF")
            time.sleep(1)
            current_path = None
            container = None
            continue
        except Exception as e:
            print(f"[DEC] decode error: {e}")
            current_path = None
            container = None
            continue

        try:
            resampled = resampler.resample(frame)
            if not isinstance(resampled, list):
                resampled = [resampled] if resampled else []
            for r in resampled:
                arr = r.to_ndarray()
                if arr.ndim == 2: arr = arr.T
                arr = np.clip(arr, -1.0, 1.0).astype(np.float32)
                buffer_push(arr)
        except Exception as e:
            print(f"[DEC] process error: {e}")

    if container:
        try: container.close()
        except: pass


def audio_callback(outdata, frames, time_info, status):
    """КРИТИЧНО: samples_output += frames, а НЕ n.
    Время идёт всегда, независимо от наличия данных в буфере."""
    global samples_output

    with state_lock:
        st = tv_state

    if st != "PLAY":
        outdata.fill(0)
        with playback_lock:
            samples_output += frames
        return

    block = buffer_pull(frames)
    if block is None or len(block) == 0:
        outdata.fill(0)
    else:
        n = min(len(block), frames)
        outdata[:n] = block[:n]
        if n < frames:
            outdata[n:] = 0

    with playback_lock:
        samples_output += frames


def main():
    print("=== Audio Sync Receiver v5 ===")
    print(f"Маппинг: {SMB_PREFIX}* -> {LOCAL_PREFIX}*")
    print(f"Порог seek: {SEEK_THRESHOLD_MS}ms")

    threading.Thread(target=udp_thread, daemon=True).start()
    threading.Thread(target=decoder_thread, daemon=True).start()

    try:
        with sd.OutputStream(samplerate=SAMPLE_RATE, channels=CHANNELS,
                             dtype='float32', blocksize=BLOCK_SIZE,
                             callback=audio_callback):
            print("[Audio] запущено, Ctrl+C для остановки")
            while not stop_event.is_set():
                time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nОстановка...")
    finally:
        stop_event.set()
        time.sleep(0.5)


if __name__ == "__main__":
    main()