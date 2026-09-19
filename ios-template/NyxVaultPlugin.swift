import Foundation
import CryptoKit
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation
import AVKit
import LocalAuthentication
import Capacitor

@objc(NyxVaultPlugin)
public class NyxVaultPlugin: CAPPlugin, CAPBridgedPlugin, PHPickerViewControllerDelegate, UIDocumentPickerDelegate, URLSessionDelegate, URLSessionTaskDelegate, URLSessionDataDelegate {
    public let identifier = "NyxVaultPlugin"
    public let jsName = "NyxVault"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "ping", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveCredential", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "changeCredential", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifySecret", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyCredential", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPrivateSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPrivateSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enrollBiometric", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disableBiometric", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "authenticateBiometric", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setSecureScreen", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enterVaultSecurity", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exitVaultSecurity", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearTempCache", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickMedia", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "syncUploads", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listMedia", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getThumbnail", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openMedia", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteMediaBatch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restoreMediaBatch", returnType: CAPPluginReturnPromise)
    ]

    private let cloud = "dpinyff2"
    private let apiKey = "731819118728455"
    private let apiSecret = "KyDKRfs_eY0i1c3r6QsXTHUrJu4"
    private let metaName = "nyx-media.json"
    private let folderName = "NYX"
    private let queue = DispatchQueue(label: "app.nyxvault.uploads", qos: .utility)
    private var pickerCall: CAPPluginCall?
    private var uploadSession: URLSession!

    private var root: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(folderName, isDirectory: true) }
    private var metaURL: URL { root.appendingPathComponent(metaName) }
    private var defaults: UserDefaults { UserDefaults.standard }

    public override func load() {
        super.load()
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let cfg = URLSessionConfiguration.background(withIdentifier: "app.nyxvault.cloudinary.background")
        cfg.isDiscretionary = false
        cfg.sessionSendsLaunchEvents = true
        cfg.waitsForConnectivity = true
        uploadSession = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    @objc func ping(_ call: CAPPluginCall) { call.resolve(["ok": true]) }

    @objc func saveCredential(_ call: CAPPluginCall) {
        let secret = call.getString("secret", "").trimmingCharacters(in: .whitespacesAndNewlines)
        let pin = call.getString("pin", "")
        guard !secret.isEmpty, pin.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else { call.reject("Invalid credential"); return }
        let salt = randomBytes(16)
        defaults.set(Data(salt).base64EncodedString(), forKey: "nyx.salt")
        defaults.set(hash(pin + ":" + secret, salt: salt), forKey: "nyx.verifier")
        defaults.set(hash(secret, salt: salt), forKey: "nyx.secretVerifier")
        defaults.set(false, forKey: "nyx.biometricEnabled")
        call.resolve()
    }

    @objc func changeCredential(_ call: CAPPluginCall) {
        let oldSecret = call.getString("oldSecret", "").trimmingCharacters(in: .whitespacesAndNewlines)
        let oldPin = call.getString("oldPin", "")
        let newSecretRaw = call.getString("newSecret", "").trimmingCharacters(in: .whitespacesAndNewlines)
        let newPin = call.getString("newPin", "")
        guard !oldSecret.isEmpty, oldPin.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else { call.reject("Enter your current secret name and 6-digit PIN"); return }
        if !newPin.isEmpty && newPin.range(of: "^[0-9]{6}$", options: .regularExpression) == nil { call.reject("New PIN must be exactly 6 digits"); return }
        guard verify(secret: oldSecret, pin: oldPin) else { call.reject("Current secret name or PIN is wrong"); return }
        let newSecret = newSecretRaw.isEmpty ? oldSecret : newSecretRaw
        let finalPin = newPin.isEmpty ? oldPin : newPin
        let salt = randomBytes(16)
        defaults.set(Data(salt).base64EncodedString(), forKey: "nyx.salt")
        defaults.set(hash(finalPin + ":" + newSecret, salt: salt), forKey: "nyx.verifier")
        defaults.set(hash(newSecret, salt: salt), forKey: "nyx.secretVerifier")
        call.resolve(["ok": true])
    }

    private func randomBytes(_ count: Int) -> [UInt8] { (0..<count).map { _ in UInt8.random(in: 0...255) } }
    private func hash(_ value: String, salt: [UInt8]) -> String {
        // Stable SHA-256 verifier for cross-platform credential checks.
        var data = Data(salt); data.append(contentsOf: value.data(using: .utf8) ?? Data())
        for _ in 0..<100_000 { data = SHA256(data) }
        return data.base64EncodedString()
    }
    private func SHA256(_ data: Data) -> Data { Data(CryptoKit.SHA256.hash(data: data)) }
    private func verify(secret: String, pin: String) -> Bool {
        guard let saltData = Data(base64Encoded: defaults.string(forKey: "nyx.salt") ?? ""), let verifier = defaults.string(forKey: "nyx.verifier") else { return false }
        return hash(pin + ":" + secret, salt: [UInt8](saltData)) == verifier
    }

    @objc func verifySecret(_ call: CAPPluginCall) {
        let secret = call.getString("secret", "")
        guard let saltData = Data(base64Encoded: defaults.string(forKey: "nyx.salt") ?? ""), let verifier = defaults.string(forKey: "nyx.secretVerifier") else { call.resolve(["ok": false]); return }
        call.resolve(["ok": hash(secret, salt: [UInt8](saltData)) == verifier])
    }
    @objc func verifyCredential(_ call: CAPPluginCall) { call.resolve(["ok": verify(secret: call.getString("secret", ""), pin: call.getString("pin", ""))]) }

    @objc func getPrivateSettings(_ call: CAPPluginCall) {
        let ctx = LAContext(); var err: NSError?
        let available = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
        call.resolve(["biometricAvailable": available, "biometricEnabled": defaults.bool(forKey: "nyx.biometricEnabled"), "removeOriginal": false, "blockScreenCapture": defaults.object(forKey: "nyx.blockScreenCapture") == nil ? true : defaults.bool(forKey: "nyx.blockScreenCapture")])
    }
    @objc func setPrivateSettings(_ call: CAPPluginCall) { defaults.set(call.getBool("biometricEnabled", false), forKey: "nyx.biometricEnabled"); call.resolve() }
    @objc func setSecureScreen(_ call: CAPPluginCall) { defaults.set(call.getBool("blockScreenCapture", true), forKey: "nyx.blockScreenCapture"); call.resolve() }
    @objc func enterVaultSecurity(_ call: CAPPluginCall) { call.resolve() }
    @objc func exitVaultSecurity(_ call: CAPPluginCall) { call.resolve() }
    @objc func clearTempCache(_ call: CAPPluginCall) { call.resolve() }

    @objc func enrollBiometric(_ call: CAPPluginCall) {
        guard verify(secret: call.getString("secret", ""), pin: call.getString("pin", "")) else { call.reject("Current secret name or PIN is wrong"); return }
        let ctx = LAContext(); ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Confirm your identity for NYX") { ok, error in
            DispatchQueue.main.async { if ok { self.defaults.set(true, forKey: "nyx.biometricEnabled"); call.resolve() } else { call.reject(error?.localizedDescription ?? "Biometric authentication failed") } }
        }
    }
    @objc func disableBiometric(_ call: CAPPluginCall) { defaults.set(false, forKey: "nyx.biometricEnabled"); call.resolve() }
    @objc func authenticateBiometric(_ call: CAPPluginCall) {
        guard defaults.bool(forKey: "nyx.biometricEnabled") else { call.reject("Biometric unlock is disabled"); return }
        let ctx = LAContext(); ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Unlock NYX") { ok, error in DispatchQueue.main.async { ok ? call.resolve() : call.reject(error?.localizedDescription ?? "Biometric authentication failed") } }
    }

    @objc func pickMedia(_ call: CAPPluginCall) {
        pickerCall = call
        if (call.getString("source", "files") == "gallery" || call.getString("source", "files") == "photos") {
            var config = PHPickerConfiguration(photoLibrary: .shared()); config.selectionLimit = 50; config.filter = .any(of: [.images, .videos])
            DispatchQueue.main.async { let picker = PHPickerViewController(configuration: config); picker.delegate = self; self.bridge?.viewController?.present(picker, animated: true) }
        } else {
            let types: [UTType] = [.image, .movie, .audio]
            DispatchQueue.main.async { let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true); picker.allowsMultipleSelection = true; picker.delegate = self; self.bridge?.viewController?.present(picker, animated: true) }
        }
    }

    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true); let call = pickerCall; pickerCall = nil
        guard !results.isEmpty else { call?.reject("Media picker cancelled"); return }
        queue.async { let count = results.compactMap { self.importProvider($0.itemProvider) }.count; DispatchQueue.main.async { count > 0 ? call?.resolve(["imported": count]) : call?.reject("Could not import selected media") } }
    }
    private func importProvider(_ provider: NSItemProvider) -> Bool {
        let type: UTType? = provider.registeredTypeIdentifiers.compactMap { UTType($0) }.first(where: { $0.conforms(to: .image) || $0.conforms(to: .movie) || $0.conforms(to: .audio) })
        guard let type else { return false }
        let sem = DispatchSemaphore(value: 0); var ok = false
        provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
            defer { sem.signal() }; guard let url else { return }
            ok = self.copyIntoVault(url: url, mime: self.mime(for: type))
        }; sem.wait(); return ok
    }
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        controller.dismiss(animated: true); let call = pickerCall; pickerCall = nil
        queue.async { let count = urls.map { self.copyIntoVault(url: $0, mime: UTType(filenameExtension: $0.pathExtension)?.preferredMIMEType ?? "application/octet-stream") }.filter { $0 }.count; DispatchQueue.main.async { count > 0 ? call?.resolve(["imported": count]) : call?.reject("Could not import selected media") } }
    }
    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { controller.dismiss(animated: true); pickerCall?.reject("Media picker cancelled"); pickerCall = nil }
    private func mime(for type: UTType) -> String { type.preferredMIMEType ?? "application/octet-stream" }
    private func copyIntoVault(url: URL, mime: String) -> Bool {
        let id = "\(Int(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(8))"; let name = url.lastPathComponent.isEmpty ? "media" : url.lastPathComponent; let out = root.appendingPathComponent(id + "." + (url.pathExtension.isEmpty ? "bin" : url.pathExtension))
        do { try FileManager.default.copyItem(at: url, to: out); appendMeta(["id": id, "name": name, "mime": mime, "size": (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0, "uploaded": false, "path": out.path]); scheduleUpload(id: id); return true } catch { return false }
    }

    @objc func syncUploads(_ call: CAPPluginCall) { queue.async { self.loadMeta().filter { !($0["uploaded"] as? Bool ?? false) }.compactMap { $0["id"] as? String }.forEach { self.scheduleUpload(id: $0) }; call.resolve() } }

    @objc func listMedia(_ call: CAPPluginCall) {
        let items = loadMeta().compactMap { row -> [String: Any]? in guard let id = row["id"] as? String, let path = row["path"] as? String, FileManager.default.fileExists(atPath: path) else { return nil }; var x = row; x["id"] = id; x["path"] = path; return x }.sorted { ($0["createdAt"] as? Double ?? 0) > ($1["createdAt"] as? Double ?? 0) }
        call.resolve(["items": items])
    }

    @objc func getThumbnail(_ call: CAPPluginCall) {
        guard let id = call.getString("id", ""), let row = loadMeta().first(where: { $0["id"] as? String == id }), let path = row["path"] as? String else { call.reject("Thumbnail unavailable"); return }
        queue.async { var image: UIImage?
            if (row["mime"] as? String ?? "").hasPrefix("video/") { let asset = AVAsset(url: URL(fileURLWithPath: path)); let gen = AVAssetImageGenerator(asset: asset); gen.appliesPreferredTrackTransform = true; image = try? UIImage(cgImage: gen.copyCGImage(at: .zero, actualTime: nil)) }
            else { image = UIImage(contentsOfFile: path) }
            guard let image, let data = image.jpegData(compressionQuality: 0.82) else { call.reject("Thumbnail unavailable"); return }
            call.resolve(["data": data.base64EncodedString(), "mime": "image/jpeg"])
        }
    }

    @objc func openMedia(_ call: CAPPluginCall) {
        guard let id = call.getString("id", ""), let row = loadMeta().first(where: { $0["id"] as? String == id }), let path = row["path"] as? String else { call.reject("Media not found"); return }
        DispatchQueue.main.async { let vc = NyxMediaViewerViewController(url: URL(fileURLWithPath: path), mime: row["mime"] as? String ?? ""); self.bridge?.viewController?.present(vc, animated: true); call.resolve(["opened": true]) }
    }

    @objc func deleteMediaBatch(_ call: CAPPluginCall) { let ids = call.getArray("ids", String.self) ?? []; queue.async { var n = 0; for id in ids { if let row = self.loadMeta().first(where: { $0["id"] as? String == id }), let path = row["path"] as? String { try? FileManager.default.removeItem(atPath: path); n += 1 } }; self.removeMeta(ids); call.resolve(["deleted": n, "failed": []]) } }
    @objc func restoreMediaBatch(_ call: CAPPluginCall) { call.reject("Restore to Photos is not available on iPhone yet") }

    private func loadMeta() -> [[String: Any]] { guard let data = try? Data(contentsOf: metaURL), let text = String(data: data, encoding: .utf8) else { return [] }; return text.split(separator: "\n").compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] } }
    private func appendMeta(_ row: [String: Any]) { var rows = loadMeta(); var x = row; x["createdAt"] = Date().timeIntervalSince1970; rows.append(x); writeMeta(rows) }
    private func writeMeta(_ rows: [[String: Any]]) { let text = rows.compactMap { try? JSONSerialization.data(withJSONObject: $0).flatMap { String(data: $0, encoding: .utf8) } }.joined(separator: "\n"); try? text.data(using: .utf8)?.write(to: metaURL, options: .atomic) }
    private func removeMeta(_ ids: [String]) { writeMeta(loadMeta().filter { !ids.contains($0["id"] as? String ?? "") }) }

    private func scheduleUpload(id: String) {
        guard let row = loadMeta().first(where: { $0["id"] as? String == id }), let path = row["path"] as? String else { return }
        let fileURL = URL(fileURLWithPath: path); guard FileManager.default.fileExists(atPath: path) else { return }
        let bodyURL = root.appendingPathComponent("upload_\(id).body")
        queue.async {
            if !FileManager.default.fileExists(atPath: bodyURL.path) { self.makeMultipartBody(file: fileURL, bodyURL: bodyURL) }
            var req = URLRequest(url: URL(string: "https://api.cloudinary.com/v1_1/\(self.cloud)/\(String((row["mime"] as? String ?? "").hasPrefix("video/") || (row["mime"] as? String ?? "").hasPrefix("audio/") ? "video" : "image"))/upload")!); req.httpMethod = "POST"; req.setValue("Basic \(Data("\(self.apiKey):\(self.apiSecret)".utf8).base64EncodedString())", forHTTPHeaderField: "Authorization"); req.setValue("multipart/form-data; boundary=NYXBOUNDARY", forHTTPHeaderField: "Content-Type")
            let task = self.uploadSession.uploadTask(with: req, fromFile: bodyURL); task.taskDescription = id; task.resume()
        }
    }
    private func makeMultipartBody(file: URL, bodyURL: URL) { var data = Data(); let boundary = "NYXBOUNDARY"; data.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"folder\"\r\n\r\nnyx-vault\r\n".utf8)); data.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"public_id\"\r\n\r\n\(file.deletingPathExtension().lastPathComponent)\r\n".utf8)); data.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(file.lastPathComponent)\"\r\nContent-Type: application/octet-stream\r\n\r\n".utf8)); data.append((try? Data(contentsOf: file)) ?? Data()); data.append(Data("\r\n--\(boundary)--\r\n".utf8)); try? data.write(to: bodyURL, options: .atomic) }
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { guard let id = task.taskDescription, error == nil else { return }; if let row = loadMeta().first(where: { $0["id"] as? String == id }), let path = row["path"] as? String { var x = row; x["uploaded"] = true; x["path"] = path; writeMeta(loadMeta().map { ($0["id"] as? String == id) ? x : $0 }); try? FileManager.default.removeItem(at: root.appendingPathComponent("upload_\(id).body")) } }
}
