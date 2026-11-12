//! File drops from other apps on Wayland. Quire adds this to Rune's winit: the compositor
//! offers `text/uri-list`, and a drop becomes the `DroppedFile` events that X11, macOS and
//! Windows already send. The pointer moves to the drop point first, so the drop lands on the
//! element under it.

use std::io::Read;
use std::path::PathBuf;

use sctk::data_device_manager::data_device::{DataDeviceData, DataDeviceHandler};
use sctk::data_device_manager::data_offer::{DataOfferHandler, DragOffer};
use sctk::data_device_manager::data_source::DataSourceHandler;
use sctk::data_device_manager::WritePipe;
use sctk::reexports::calloop::PostAction;
use sctk::reexports::client::protocol::wl_data_device::WlDataDevice;
use sctk::reexports::client::protocol::wl_data_device_manager::DndAction;
use sctk::reexports::client::protocol::wl_data_source::WlDataSource;
use sctk::reexports::client::protocol::wl_surface::WlSurface;
use sctk::reexports::client::{Connection, Proxy, QueueHandle};

use crate::dpi::{LogicalPosition, PhysicalPosition};
use crate::event::WindowEvent;
use crate::platform_impl::wayland::state::WinitState;
use crate::platform_impl::wayland::{self, DeviceId};

const URI_LIST: &str = "text/uri-list";

fn offer(device: &WlDataDevice) -> Option<DragOffer> {
    device.data::<DataDeviceData>()?.drag_offer()
}

/// The local paths in a `text/uri-list`: one `file:` URI a line, `#` lines are comments.
fn paths(list: &str) -> Vec<PathBuf> {
    list.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter_map(|l| l.strip_prefix("file://"))
        // `file://host/path`: the path starts at the first slash.
        .filter_map(|rest| rest.find('/').map(|i| &rest[i..]))
        .map(|p| PathBuf::from(decode(p)))
        .collect()
}

fn decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() && b[i + 1].is_ascii_hexdigit() && b[i + 2].is_ascii_hexdigit() {
            let v = |c: u8| (c as char).to_digit(16).unwrap_or(0) as u8;
            out.push(v(b[i + 1]) * 16 + v(b[i + 2]));
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

impl WinitState {
    /// The pointer position on `surface`'s window, so the window knows where the drag is.
    fn dnd_cursor(&mut self, surface: &WlSurface, x: f64, y: f64) {
        let window_id = wayland::make_wid(surface);
        let scale = match self.windows.get_mut().get(&window_id) {
            Some(w) => w.lock().map(|w| w.scale_factor()).unwrap_or(1.0),
            None => return,
        };
        let position: PhysicalPosition<f64> = LogicalPosition::new(x, y).to_physical(scale);
        let device_id = crate::event::DeviceId(crate::platform_impl::DeviceId::Wayland(DeviceId));
        self.events_sink.push_window_event(WindowEvent::CursorMoved { device_id, position }, window_id);
    }
}

impl DataDeviceHandler for WinitState {
    fn enter(&mut self, _: &Connection, _: &QueueHandle<Self>, device: &WlDataDevice, x: f64, y: f64, surface: &WlSurface) {
        let Some(offer) = offer(device) else { return };
        if offer.with_mime_types(|m| m.iter().any(|t| t == URI_LIST)) {
            offer.accept_mime_type(offer.serial, Some(URI_LIST.to_string()));
            offer.set_actions(DndAction::Copy, DndAction::Copy);
        } else {
            offer.accept_mime_type(offer.serial, None);
        }
        self.dnd_cursor(surface, x, y);
    }

    fn leave(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataDevice) {}

    fn motion(&mut self, _: &Connection, _: &QueueHandle<Self>, device: &WlDataDevice, x: f64, y: f64) {
        if let Some(offer) = offer(device) {
            self.dnd_cursor(&offer.surface, x, y);
        }
    }

    fn selection(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataDevice) {}

    fn drop_performed(&mut self, _: &Connection, _: &QueueHandle<Self>, device: &WlDataDevice) {
        let Some(offer) = offer(device) else { return };
        if !offer.with_mime_types(|m| m.iter().any(|t| t == URI_LIST)) {
            offer.finish();
            offer.destroy();
            return;
        }
        let pipe = match offer.receive(URI_LIST.to_string()) {
            Ok(p) => p,
            Err(e) => {
                tracing::warn!("Could not read the dropped files: {e}");
                offer.destroy();
                return;
            },
        };
        let window_id = wayland::make_wid(&offer.surface);
        let (x, y, surface) = (offer.x, offer.y, offer.surface.clone());
        let mut data = Vec::new();
        let added = self.loop_handle.insert_source(pipe, move |_, f, state| {
            // SAFETY: the file stays open; it closes with the source.
            let file = unsafe { f.get_mut() };
            let mut buf = [0u8; 4096];
            match file.read(&mut buf) {
                Ok(0) => {
                    state.dnd_cursor(&surface, x, y);
                    for path in paths(&String::from_utf8_lossy(&data)) {
                        state.events_sink.push_window_event(WindowEvent::DroppedFile(path), window_id);
                    }
                    offer.finish();
                    offer.destroy();
                    PostAction::Remove
                },
                Ok(n) => {
                    data.extend_from_slice(&buf[..n]);
                    PostAction::Continue
                },
                Err(e) if matches!(e.kind(), std::io::ErrorKind::Interrupted | std::io::ErrorKind::WouldBlock) => PostAction::Continue,
                Err(e) => {
                    tracing::warn!("Could not read the dropped files: {e}");
                    offer.destroy();
                    PostAction::Remove
                },
            }
        });
        if let Err(e) = added {
            tracing::warn!("Could not wait for the dropped files: {e}");
        }
    }
}

impl DataOfferHandler for WinitState {
    fn source_actions(&mut self, _: &Connection, _: &QueueHandle<Self>, offer: &mut DragOffer, _: DndAction) {
        offer.set_actions(DndAction::Copy, DndAction::Copy);
    }

    fn selected_action(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &mut DragOffer, _: DndAction) {}
}

/// Winit offers no data of its own, so these never run.
impl DataSourceHandler for WinitState {
    fn accept_mime(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource, _: Option<String>) {}
    fn send_request(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource, _: String, _: WritePipe) {}
    fn cancelled(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource) {}
    fn dnd_dropped(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource) {}
    fn dnd_finished(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource) {}
    fn action(&mut self, _: &Connection, _: &QueueHandle<Self>, _: &WlDataSource, _: DndAction) {}
}

sctk::delegate_data_device!(WinitState);

#[cfg(test)]
mod tests {
    #[test]
    fn uri_lists_become_paths() {
        let list = "# from a file manager\r\nfile:///home/me/My%20cat.png\r\nfile://host/tmp/a.jpg\r\nhttps://example.com/x.png\r\n";
        assert_eq!(super::paths(list), vec![std::path::PathBuf::from("/home/me/My cat.png"), std::path::PathBuf::from("/tmp/a.jpg")]);
    }
}
