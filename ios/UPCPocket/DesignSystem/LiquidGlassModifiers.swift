import SwiftUI

struct UPCGlassGroup<Content: View>: View {
    let spacing: CGFloat?
    @ViewBuilder let content: () -> Content

    init(spacing: CGFloat? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.spacing = spacing
        self.content = content
    }

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                content()
            }
        } else {
            content()
        }
    }
}

@available(iOS 26.0, *)
private func makeUPCGlass(tint: Color?, interactive: Bool) -> Glass {
    let base = tint.map { Glass.regular.tint($0) } ?? Glass.regular
    return interactive ? base.interactive() : base
}

extension View {
    @ViewBuilder
    func upcGlassSurface<S: InsettableShape>(
        in shape: S,
        tint: Color? = nil,
        interactive: Bool = false
    ) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(makeUPCGlass(tint: tint, interactive: interactive), in: shape)
        } else {
            self
                .background(.ultraThinMaterial, in: shape)
                .overlay {
                    shape.strokeBorder(.primary.opacity(0.08), lineWidth: 0.5)
                }
        }
    }

    func upcGlassCapsule(tint: Color? = nil, interactive: Bool = false) -> some View {
        upcGlassSurface(in: Capsule(), tint: tint, interactive: interactive)
    }

    @ViewBuilder
    func upcTabBarMinimizeOnScroll() -> some View {
        if #available(iOS 26.0, *) {
            self.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}
