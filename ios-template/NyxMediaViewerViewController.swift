import UIKit
import AVKit

final class NyxMediaViewerViewController: UIViewController {
    private let url: URL
    private let mime: String
    init(url: URL, mime: String) { self.url = url; self.mime = mime; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if mime.hasPrefix("video/") || mime.hasPrefix("audio/") {
            let player = AVPlayer(url: url)
            let vc = AVPlayerViewController(); vc.player = player; vc.modalPresentationStyle = .fullScreen
            present(vc, animated: true) { player.play() }
        } else {
            view.backgroundColor = .black
            let imageView = UIImageView(image: UIImage(contentsOfFile: url.path)); imageView.frame = view.bounds; imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]; imageView.contentMode = .scaleAspectFit; imageView.backgroundColor = .black
            let close = UIButton(type: .system); close.setTitle("Done", for: .normal); close.tintColor = .white; close.frame = CGRect(x: 16, y: 50, width: 70, height: 44); close.addTarget(self, action: #selector(done), for: .touchUpInside)
            view.addSubview(imageView); view.addSubview(close)
        }
    }
    @objc private func done() { dismiss(animated: true) }
}
