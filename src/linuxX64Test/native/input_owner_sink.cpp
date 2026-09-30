extern "C" {
unsigned fixture_input_owner_calls = 0;
void fixture_input_owner(const void*) { ++fixture_input_owner_calls; }
void fixture_player_initialize(void* player) { *static_cast<unsigned char*>(player) = 0; }
}
