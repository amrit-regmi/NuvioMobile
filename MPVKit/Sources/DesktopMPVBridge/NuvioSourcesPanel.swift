import SwiftUI

struct NuvioSourcesPanel: View {
    @ObservedObject var state: NuvioPlayerState
    var onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.52)
                .onTapGesture { onDismiss() }

            VStack(spacing: 0) {
                HStack {
                    Text("Sources")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    HStack(spacing: 8) {
                        panelChipButton(label: "Reload", icon: "arrow.clockwise") {
                            state.sourcesLoading = true
                            if !state.sourceAddonGroups.isEmpty {
                                state.sourceAddonGroups = state.sourceAddonGroups.map {
                                    NuvioAddonGroupInfo(
                                        id: $0.id,
                                        addonName: $0.addonName,
                                        addonId: $0.addonId,
                                        isLoading: true,
                                        hasError: false
                                    )
                                }
                            }
                            state.sourceReloadRequested = true
                        }
                        panelChipButton(label: "Close", icon: nil) {
                            onDismiss()
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 12)

                let addonGroups = addonFilterGroups
                if addonGroups.count > 1 || state.sourcesLoading {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            if addonGroups.count > 1 {
                                addonFilterChip(
                                    label: "All",
                                    isSelected: state.sourceSelectedFilter == nil,
                                    isLoading: state.sourcesLoading,
                                    hasError: false
                                ) {
                                    state.sourceSelectedFilter = nil
                                    state.sourceFilterSelectedValue = nil
                                    state.sourceFilterChanged = true
                                }
                                ForEach(addonGroups) { group in
                                    addonFilterChip(
                                        label: group.addonName,
                                        isSelected: state.sourceSelectedFilter == group.addonId,
                                        isLoading: group.isLoading,
                                        hasError: group.hasError
                                    ) {
                                        state.sourceSelectedFilter = group.addonId
                                        state.sourceFilterSelectedValue = group.addonId
                                        state.sourceFilterChanged = true
                                    }
                                }
                            } else {
                                sourceLoadingChip(label: addonGroups.first?.addonName ?? "Fetching sources")
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .padding(.bottom, 12)
                }

                let streams = state.filteredSourceStreams
                if state.sourcesLoading && streams.isEmpty {
                    sourceLoadingState
                } else if streams.isEmpty {
                    sourceEmptyState
                } else {
                    ScrollView {
                        VStack(spacing: 6) {
                            if state.sourcesLoading {
                                sourceProgressRow
                            }
                            ForEach(streams) { stream in
                                sourceStreamRow(stream: stream) {
                                    state.sourceStreamSelectedUrl = stream.url
                                    onDismiss()
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                    }
                }
            }
            .frame(maxWidth: 520)
            .frame(maxHeight: 600)
            .background(Color(red: 0.12, green: 0.12, blue: 0.12))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
        }
    }

    var addonFilterGroups: [NuvioAddonGroupInfo] {
        var seen = Set<String>()
        return state.sourceAddonGroups.filter { group in
            let key = group.addonName.isEmpty ? group.addonId : group.addonName
            if seen.contains(key) { return false }
            seen.insert(key)
            return true
        }
    }

    var sourceLoadingState: some View {
        VStack(spacing: 12) {
            Spacer()
            ProgressView()
                .progressViewStyle(.circular)
                .scaleEffect(0.9)
                .frame(width: 32, height: 32)
            Text("Fetching sources")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.white.opacity(0.74))
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 180)
    }

    var sourceEmptyState: some View {
        VStack(spacing: 10) {
            Spacer()
            Image(systemName: "magnifyingglass")
                .font(.system(size: 24, weight: .semibold))
                .foregroundColor(.white.opacity(0.4))
            Text("No streams found")
                .font(.system(size: 14))
                .foregroundColor(.white.opacity(0.6))
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 180)
    }

    var sourceProgressRow: some View {
        HStack(spacing: 10) {
            ProgressView()
                .progressViewStyle(.circular)
                .scaleEffect(0.55)
                .frame(width: 16, height: 16)
            Text("Fetching more sources")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(.white.opacity(0.68))
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    func sourceLoadingChip(label: String) -> some View {
        HStack(spacing: 6) {
            ProgressView()
                .progressViewStyle(.circular)
                .scaleEffect(0.5)
                .frame(width: 12, height: 12)
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.white.opacity(0.78))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.08))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    func sourceStreamRow(stream: NuvioStreamInfo, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(stream.label)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        if stream.isCurrent {
                            Text("Playing")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Color.white.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    if let sub = stream.subtitle, !sub.isEmpty, sub != stream.label {
                        Text(sub)
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.6))
                            .lineLimit(2)
                    }
                    HStack(spacing: 8) {
                        if stream.videoSize > 0 {
                            streamSizeBadge(bytes: stream.videoSize)
                        }
                        Text(stream.addonName)
                            .font(.system(size: 11))
                            .italic()
                            .foregroundColor(.white.opacity(0.6))
                            .lineLimit(1)
                    }
                }
                Spacer()
                if stream.isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(stream.isCurrent ? Color.white.opacity(0.12) : Color.white.opacity(0.05))
            .overlay(
                stream.isCurrent ?
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white.opacity(0.2), lineWidth: 1) : nil
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
