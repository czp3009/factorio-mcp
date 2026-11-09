#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <utility>
#include <vector>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

// Independently compiled native storage. No game installation or game offsets are involved.
namespace agui {
class GraphStyle {};
class Graph {
  public:
    struct GraphSeries {
        std::string label;
        std::vector<float> values;
        ~GraphSeries();
    };
    uint64_t prefix[FIXTURE_PADDING];
    std::function<std::string(float)> vertical;
    std::function<std::string(unsigned)> horizontal;
    std::function<std::string(float, unsigned)> tooltip;
    uint32_t sampleCount;
    std::vector<GraphSeries> seriesData;
    float minimumValue, maximumValue;
    Graph(unsigned, std::function<std::string(float)>, std::function<std::string(unsigned)>,
          std::function<std::string(float, unsigned)>, const GraphStyle *);
    void setValues(std::vector<GraphSeries> &&);
};

Graph::GraphSeries::~GraphSeries() = default;

Graph::Graph(unsigned count, std::function<std::string(float)> v, std::function<std::string(unsigned)> h,
             std::function<std::string(float, unsigned)> t, const GraphStyle *)
    : vertical(std::move(v)), horizontal(std::move(h)), tooltip(std::move(t)), sampleCount(count),
      minimumValue(0), maximumValue(0) {}

void Graph::setValues(std::vector<GraphSeries> &&data) {
    seriesData = std::move(data);
    minimumValue = maximumValue = 0;
    for (const auto &row : seriesData) {
        for (const auto value : row.values) {
            minimumValue = std::min(minimumValue, value);
            maximumValue = std::max(maximumValue, value);
        }
    }
}
}

extern "C" {
uint64_t fixture_graph_fields[] = {
    sizeof(agui::Graph), offsetof(agui::Graph, sampleCount), offsetof(agui::Graph, minimumValue),
    offsetof(agui::Graph, maximumValue), offsetof(agui::Graph, seriesData), sizeof(agui::Graph::GraphSeries),
    offsetof(agui::Graph::GraphSeries, values), offsetof(agui::Graph::GraphSeries, label), sizeof(std::string),
};
}

int main() {
    agui::Graph graph(3, {}, {}, {}, nullptr);
    std::vector<agui::Graph::GraphSeries> data;
    graph.setValues(std::move(data));
    return graph.sampleCount == 3 && graph.minimumValue == 0 && graph.maximumValue == 0 ? 0 : 1;
}
