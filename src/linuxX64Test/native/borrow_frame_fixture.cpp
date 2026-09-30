extern "C" void fixture_borrow_frame(void **value) {
    // A separately compiled callee can change its borrowed local storage.
    *value = nullptr;
}
