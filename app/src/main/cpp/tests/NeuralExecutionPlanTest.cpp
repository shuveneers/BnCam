#include "vulkan/VulkanNeuralExecutionPlan.h"

#include <cassert>
#include <iostream>

int main(int argc, char** argv) {
    using namespace bncam::vulkan::neural;
    if (argc < 2) return 2;

    const auto model = loadNeuralModelPackageFile(argv[1]);
    assert(model.valid);

    // Preserve the original compact execution-plan contract test.
    const auto compact = buildNeuralExecutionPlan(model, 200u, 150u, 3u);
    if (!compact.valid) {
        std::cerr << compact.failureReason << "\n";
        return 3;
    }
    assert(compact.tiles.paddedWidth == 200u && compact.tiles.paddedHeight == 152u);
    assert(compact.tileCount == 12u);
    assert(compact.tiles.halo == 40u);
    assert((compact.tiles.tileInputWidth() % 8u) == 0u);
    assert(compact.kernelDispatchesPerTile == 92u);
    assert(compact.persistentArenaBytesPerSlot == compact.activationBytesPerSlot * 3u);

    const auto first = neuralTileAt(compact.tiles, 0u);
    assert(first.inputX == 0 && first.inputY == 0 && first.validX == 0 && first.validY == 0);
    assert(first.inputWidth == 104u && first.inputHeight == 104u);
    const auto second = neuralTileAt(compact.tiles, 1u);
    assert(second.inputX == 24 && second.validX == 40u && second.inputWidth == 144u);

    bool copy = false;
    bool film = false;
    bool gate = false;
    bool write = false;
    bool upsample = false;
    for (const auto& op : compact.ops) {
        copy |= op.primitive == NeuralPrimitive::Copy;
        film |= op.primitive == NeuralPrimitive::Film;
        gate |= op.primitive == NeuralPrimitive::SimpleGate;
        write |= op.primitive == NeuralPrimitive::Writeback;
        upsample |= op.upsample2x;
        if (op.primitive == NeuralPrimitive::Conv ||
            op.primitive == NeuralPrimitive::Film ||
            op.primitive == NeuralPrimitive::SimpleGate ||
            op.primitive == NeuralPrimitive::ScaledAdd ||
            op.primitive == NeuralPrimitive::Add) {
            assert(op.src0 != op.dst);
        }
    }
    assert(copy && film && gate && write && upsample);

    // Phase 8 regression: these are the packed Bayer dimensions corresponding
    // to the observed 4080x3072 tele and 4096x3072 main RAW_SENSOR captures.
    // Both must use the same model/path and both must be valid with the single
    // production scratch slot used by the serialized Neural capture route.
    const auto tele = buildNeuralExecutionPlan(model, 2040u, 1536u, 1u);
    const auto main = buildNeuralExecutionPlan(model, 2048u, 1536u, 1u);
    const auto teleTriple = buildNeuralExecutionPlan(model, 2040u, 1536u, 3u);

    assert(tele.valid);
    assert(main.valid);
    assert(teleTriple.valid);
    assert(tele.tiles.paddedWidth == 2040u);
    assert(tele.tiles.paddedHeight == 1536u);
    assert(main.tiles.paddedWidth == 2048u);
    assert(main.tiles.paddedHeight == 1536u);

    // Frame dimensions change tile count, not the per-slot scratch geometry.
    assert(tele.activationBytesPerSlot == main.activationBytesPerSlot);
    assert(tele.persistentArenaBytesPerSlot == tele.activationBytesPerSlot);
    assert(teleTriple.persistentArenaBytesPerSlot == tele.activationBytesPerSlot * 3u);

    std::cout << "NeuralExecutionPlanTest PASS ops=" << compact.ops.size()
              << " dispatches=" << compact.kernelDispatchesPerTile
              << " teleArena1=" << tele.persistentArenaBytesPerSlot
              << " teleArena3=" << teleTriple.persistentArenaBytesPerSlot
              << "\n";
    return 0;
}
