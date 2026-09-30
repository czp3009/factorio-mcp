#include "../../nativeMain/native/key_gesture.h"
#include <cassert>
#include <set>

template <class Action> static void rejects(Action action) {
    bool rejected = false;
    try {
        action();
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected);
}

int main() {
    const uint32_t keys[] = {41, 7, 219, 5, 31, 99, 77, 3};
    for (unsigned length = 1; length <= 8; ++length) {
        // Cancel before admission, between every edge, and after all edges. -1 runs to completion.
        for (int cancelAt = -1; cancelAt <= static_cast<int>(2 * length); ++cancelAt) {
            KeyGesture gesture;
            gesture.begin(keys, length);
            rejects([&] { gesture.begin(keys, length); });
            std::set<uint32_t> held;
            unsigned downs = 0, ups = 0, edge = 0;
            uint32_t key = 0;
            bool down = false;
            for (;;) {
                if (static_cast<int>(edge) == cancelAt) {
                    gesture.cancel();
                    gesture.cancel();
                    assert(gesture.active() && gesture.aborted());
                }
                if (!gesture.next(key, down))
                    break;
                assert(gesture.active());
                if (down) {
                    assert(cancelAt < 0 || static_cast<int>(edge) < cancelAt);
                    assert(ups == 0 && downs < length && key == keys[downs++]);
                    assert(held.insert(key).second);
                } else {
                    assert(ups < downs && key == keys[downs - ++ups]);
                    assert(held.erase(key) == 1);
                }
                assert(++edge <= 2 * length);
            }
            assert(held.empty() && downs == ups && !gesture.active());
            assert(downs == (cancelAt < 0 ? length : std::min(length, static_cast<unsigned>(cancelAt))));
            assert(gesture.aborted() == (cancelAt >= 0));
            key = 123456;
            down = true;
            assert(!gesture.next(key, down) && key == 123456 && down);
            gesture.begin(keys, 1);
            assert(gesture.active() && !gesture.aborted());
            assert(gesture.next(key, down) && key == keys[0] && down);
            assert(gesture.next(key, down) && key == keys[0] && !down);
            assert(gesture.active());
            assert(!gesture.next(key, down) && !gesture.active());
        }
    }
    KeyGesture gesture;
    const uint32_t duplicate[] = {41, 7, 41};
    rejects([&] { gesture.begin(nullptr, 1); });
    rejects([&] { gesture.begin(keys, 0); });
    rejects([&] { gesture.begin(keys, 9); });
    rejects([&] { gesture.begin(duplicate, 3); });
    assert(!gesture.active() && !gesture.aborted());
    gesture.cancel();
    assert(!gesture.aborted());
    uint32_t key = 0;
    bool down = false;
    assert(!gesture.next(key, down));
}
