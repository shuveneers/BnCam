// Runs the production compute shaders on an Android Vulkan device, with a CPU oracle.
#include <vulkan/vulkan.h>
#include "HighlightGamutProtectionV2.h"
#include "DefaultRawColorPipeline.h"
#include "ProfileColorManagement.h"
#include "DefaultRawSceneEvidence.h"
#include <array>
#include <vector>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <cstring>
#include <cassert>
using bncam::highlight::Rgb;
constexpr unsigned W=64,H=32,N=W*H;
void check(VkResult r) { if(r!=VK_SUCCESS) throw std::runtime_error("Vulkan error "+std::to_string(r)); }
struct Buffer { VkBuffer b; VkDeviceMemory m; float* p; size_t bytes=65536; };
struct Gpu {
 VkInstance instance; VkPhysicalDevice physical; VkDevice device; VkQueue queue; unsigned family=0;
 VkCommandPool pool; VkCommandBuffer cmd;
 Gpu() {
  VkApplicationInfo a{VK_STRUCTURE_TYPE_APPLICATION_INFO};a.apiVersion=VK_API_VERSION_1_1;
  VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ic.pApplicationInfo=&a;check(vkCreateInstance(&ic,nullptr,&instance));
  unsigned n=1;check(vkEnumeratePhysicalDevices(instance,&n,&physical));
  VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(physical,&props);std::cout<<"GPU="<<props.deviceName<<"\n";
  vkGetPhysicalDeviceQueueFamilyProperties(physical,&n,nullptr);std::vector<VkQueueFamilyProperties> qs(n);vkGetPhysicalDeviceQueueFamilyProperties(physical,&n,qs.data());
  while(!(qs[family].queueFlags&VK_QUEUE_COMPUTE_BIT)) ++family;
  float priority=1;VkDeviceQueueCreateInfo qc{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qc.queueFamilyIndex=family;qc.queueCount=1;qc.pQueuePriorities=&priority;
  VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&qc;check(vkCreateDevice(physical,&dc,nullptr,&device));vkGetDeviceQueue(device,family,0,&queue);
  VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.queueFamilyIndex=family;pc.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;check(vkCreateCommandPool(device,&pc,nullptr,&pool));
  VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;check(vkAllocateCommandBuffers(device,&ca,&cmd));
 }
 Buffer buffer() {
  Buffer b{};VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bc.size=b.bytes;bc.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;check(vkCreateBuffer(device,&bc,nullptr,&b.b));
  VkMemoryRequirements mr;vkGetBufferMemoryRequirements(device,b.b,&mr);VkPhysicalDeviceMemoryProperties mp;vkGetPhysicalDeviceMemoryProperties(physical,&mp);
  unsigned i=0;while(i<mp.memoryTypeCount && (!(mr.memoryTypeBits&(1u<<i)) || (mp.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))!=(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) ++i;
  assert(i<mp.memoryTypeCount);VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ma.allocationSize=mr.size;ma.memoryTypeIndex=i;check(vkAllocateMemory(device,&ma,nullptr,&b.m));check(vkBindBufferMemory(device,b.b,b.m,0));check(vkMapMemory(device,b.m,0,b.bytes,0,(void**)&b.p));std::memset(b.p,0,b.bytes);return b;
 }
 void run(const std::string& file,const std::vector<Buffer*>& buffers, const void* push,unsigned pushSize,unsigned firstOffset=0) {
  std::ifstream in(file,std::ios::binary|std::ios::ate);assert(in.good());size_t len=in.tellg();in.seekg(0);std::vector<uint32_t> words(len/4);in.read((char*)words.data(),len);
  VkShaderModuleCreateInfo sc{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sc.codeSize=len;sc.pCode=words.data();VkShaderModule shader;check(vkCreateShaderModule(device,&sc,nullptr,&shader));
  std::vector<VkDescriptorSetLayoutBinding> bs;for(unsigned i=0;i<buffers.size();++i)bs.push_back({i,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr});
  VkDescriptorSetLayoutCreateInfo lc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};lc.bindingCount=bs.size();lc.pBindings=bs.data();VkDescriptorSetLayout layout;check(vkCreateDescriptorSetLayout(device,&lc,nullptr,&layout));
  VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT,0,pushSize};VkPipelineLayoutCreateInfo plc{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};plc.setLayoutCount=1;plc.pSetLayouts=&layout;plc.pushConstantRangeCount=1;plc.pPushConstantRanges=&range;VkPipelineLayout pl;check(vkCreatePipelineLayout(device,&plc,nullptr,&pl));
  VkComputePipelineCreateInfo cc{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};cc.layout=pl;cc.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};cc.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;cc.stage.module=shader;cc.stage.pName="main";VkPipeline pipeline;check(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cc,nullptr,&pipeline));
  VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,(unsigned)buffers.size()};VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=1;dp.poolSizeCount=1;dp.pPoolSizes=&ps;VkDescriptorPool dpool;check(vkCreateDescriptorPool(device,&dp,nullptr,&dpool));
  VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};da.descriptorPool=dpool;da.descriptorSetCount=1;da.pSetLayouts=&layout;VkDescriptorSet set;check(vkAllocateDescriptorSets(device,&da,&set));
  for(unsigned i=0;i<buffers.size();++i){size_t offset=i==0?firstOffset:0;VkDescriptorBufferInfo bi{buffers[i]->b,offset,buffers[i]->bytes-offset};VkWriteDescriptorSet wr{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};wr.dstSet=set;wr.dstBinding=i;wr.descriptorCount=1;wr.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;wr.pBufferInfo=&bi;vkUpdateDescriptorSets(device,1,&wr,0,nullptr);}
  check(vkResetCommandBuffer(cmd,0));VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};check(vkBeginCommandBuffer(cmd,&begin));
  VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
  vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pl,0,1,&set,0,nullptr);vkCmdPushConstants(cmd,pl,VK_SHADER_STAGE_COMPUTE_BIT,0,pushSize,push);vkCmdDispatch(cmd,W/16,H/16,1);
  barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr);check(vkEndCommandBuffer(cmd));VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cmd;check(vkQueueSubmit(queue,1,&si,VK_NULL_HANDLE));check(vkQueueWaitIdle(queue));
  vkDestroyDescriptorPool(device,dpool,nullptr);vkDestroyPipeline(device,pipeline,nullptr);vkDestroyPipelineLayout(device,pl,nullptr);vkDestroyDescriptorSetLayout(device,layout,nullptr);vkDestroyShaderModule(device,shader,nullptr);
 }
};
struct Push { std::array<uint32_t,32> u{}; void f(int i,float x){std::memcpy(&u[i],&x,4);} };
Rgb pbr(Rgb c) {
 for(int i=0;i<3;++i)c[i]=std::max(0.f,c[i]);float peak=std::max({c.r,c.g,c.b}),x=std::min({c.r,c.g,c.b});
 float off=(x<.08f?x-6.25f*x*x:.04f)*bncam::highlight::smoothstep(.60f,.76f,peak);for(int i=0;i<3;++i)c[i]-=off;
 peak=std::max({c.r,c.g,c.b});if(peak<.76f)return c;
 float np=1.f-.24f*.24f/(peak+.24f-.76f),g=1.f-1.f/(.15f*(peak-np)+1.f);
 for(int i=0;i<3;++i)c[i]=c[i]*(np/peak)*(1.f-g)+np*g;return c;
}
float error(const std::vector<float>& a,const float* b){float e=0;for(size_t i=0;i<a.size();++i){assert(std::isfinite(b[i]));e=std::max(e,std::abs(a[i]-b[i]));}return e;}
int main(int argc,char**argv){
 assert(argc==2);std::string dir=argv[1];Gpu gpu;std::array<Buffer,16> b;for(auto& v:b)v=gpu.buffer();
 auto& source=b[0];auto& finalized=b[1];auto& rgb=b[2];auto& color=b[3];auto& stats=b[4];auto& telemetry=b[5];auto& empty=b[6];auto& lut=b[7];auto& tone=b[8];
 float maxMapError=0,maxColorError=0,maxToneError=0;unsigned partialUnchanged=0,fullNeutral=0;
 for(int pattern=0;pattern<4;++pattern)for(int lens=0;lens<3;++lens)for(int format=0;format<2;++format){
  float quantum=format?65535.f:1023.f;std::vector<float> cells(N/4),raw(N*3);
  for(unsigned y=0;y<H;y+=2)for(unsigned x=0;x<W;x+=2){
   // All clipping masks, a dense .96..1.0 ramp, coloured textures and one-green-only clipping.
   unsigned k=(y/2)*(W/2)+x/2;float v=.96f+.04f*float(k%32)/31.f;unsigned mask=k%16;
   std::array<float,4>s;for(int ch=0;ch<4;++ch)s[ch]=std::round(((mask&(1u<<ch))?v:(.15f+.04f*((k+ch)%8)))*quantum)/quantum;
   if(y>=H-8) s={1.f,1.f,1.f,1.f};
   source.p[y*W+x]=s[0];source.p[y*W+x+1]=s[1];source.p[(y+1)*W+x]=s[2];source.p[(y+1)*W+x+1]=s[3];cells[k]=bncam::highlight::defaultRawCellConfidence(s);
   for(unsigned dy=0;dy<2;++dy)for(unsigned dx=0;dx<2;++dx){unsigned p=((y+dy)*W+x+dx)*3;raw[p]=s[0];raw[p+1]=.5f*(s[1]+s[2]);raw[p+2]=s[3];}
  }
  Push fp;fp.u[0]=W;fp.u[1]=H;fp.u[2]=pattern;fp.u[6]=1;fp.f(14,2.f);fp.f(15,1.f);fp.f(16,1.f);fp.f(17,1.f);fp.u[27]=1;
  std::memset(telemetry.p,0,telemetry.bytes);gpu.run(dir+"/spectra_raw_finalize.comp.spv",{&source,&finalized,&empty,&telemetry,&empty},fp.u.data(),112);
  for(unsigned i=0;i<N/4;++i){uint32_t bits;std::memcpy(&bits,finalized.p+N+i,4);float decoded=1.f;for(int ch=0;ch<4;++ch)decoded*=float((bits>>(ch*8))&255u)/255.f;maxMapError=std::max(maxMapError,std::abs(cells[i]-decoded));}assert(maxMapError<2e-6f);
  std::memcpy(rgb.p,raw.data(),N*3*4);std::memset(telemetry.p,0,telemetry.bytes);
  Push cp;cp.u[0]=W;cp.u[1]=H;cp.u[3]=3;std::array<float,9> matrix{1.15f+.05f*lens,-.15f-.05f*lens,0.f,-.13f,1.07f,.06f,.01f,-.6f-.1f*lens,1.59f+.1f*lens};float wr=1.6f+.2f*lens,wb=1.3f+.15f*lens;cp.f(4,wr);cp.f(5,1);cp.f(6,wb);for(int i=0;i<9;++i)cp.f(8+i,matrix[i]);cp.u[23]=1;cp.f(28,1);
  gpu.run(dir+"/spectra_demosaic_resident.comp.spv",{&finalized,&rgb,&color,&stats,&empty,&empty,&telemetry,&empty},cp.u.data(),128,N*4);
  std::vector<float> expected(N*3);
  for(unsigned y=0;y<H;++y)for(unsigned x=0;x<W;++x){unsigned p=(y*W+x)*3;float confidence=bncam::highlight::defaultRawConfidenceAt(x,y,[&](int cx,int cy){return cells[std::clamp(cy,0,int(H/2)-1)*(W/2)+std::clamp(cx,0,int(W/2)-1)];});
   Rgb c=bncam::highlight::multiplyMatrix(matrix,{raw[p]*wr,raw[p+1],raw[p+2]*wb});bncam::highlight::SensorColorConfidence ev;ev.confidence=confidence;auto out=bncam::highlight::applyClippingAwareHighlightColor(c,ev);
   if(confidence==1){assert(out.rgb.r==c.r&&out.rgb.g==c.g&&out.rgb.b==c.b);++partialUnchanged;}if(confidence==0){assert(std::abs(out.rgb.r-out.rgb.g)<1e-6);++fullNeutral;}
   for(int ch=0;ch<3;++ch)expected[p+ch]=out.rgb[ch];
  }
  maxColorError=std::max(maxColorError,error(expected,color.p));assert(maxColorError<4e-6f);
  std::memcpy(tone.p,color.p,N*3*4);std::memset(telemetry.p,0,telemetry.bytes);for(int i=0;i<4096;++i){lut.p[2*i]=i/4095.f;lut.p[2*i+1]=1;}
  Push tp;tp.u[0]=W;tp.u[1]=H;tp.u[2]=3;tp.u[6]=1;tp.f(8,1);tp.f(9,-1);
  gpu.run(dir+"/spectra_tone_resident.comp.spv",{&color,&tone,&empty,&lut,&telemetry,&empty,&empty,&empty,&empty,&empty,&empty,&empty,&empty},tp.u.data(),120);
  for(unsigned p=0;p<N*3;p+=3){Rgb c=pbr(bncam::color::mapSceneToKhronosInput({expected[p],expected[p+1],expected[p+2]}));bncamCompressToUnitGamutPreserveLuma(c.r,c.g,c.b);for(int ch=0;ch<3;++ch)expected[p+ch]=c[ch];}
  maxToneError=std::max(maxToneError,error(expected,tone.p));assert(maxToneError<1e-5f);
  std::cout<<"synthetic cfa="<<pattern<<" lens="<<lens<<" format="<<(format?"RAW_SENSOR":"RAW10")<<" pixels="<<N<<" passed\n";
 }
 // Trust intact coloured samples exactly; physical saturation loses chromaticity confidence.
 for(int i=0;i<=10000;++i){float v=.9f+.04f*i/10000.f;assert(bncam::highlight::defaultRawCellConfidence({v,.8f,.8f,v})==1.f);}assert(bncam::highlight::defaultRawCellConfidence({1.f,.8f,.8f,.8f})==0.f);
 float prevConfidence=1.f,prevY=-1.f;
 for(int i=0;i<=10000;++i){float v=.94f+.06f*i/10000.f;float confidence=bncam::highlight::defaultRawCellConfidence({v,v,v,v});assert(confidence<=prevConfidence+1e-6f);assert(prevConfidence-confidence<4.f/255.f+1e-6f);prevConfidence=confidence;}
 for(int i=0;i<=10000;++i){float v=.7f+3.3f*i/10000.f;float y=pbr({v,v,v}).r;assert(y>prevY);prevY=y;}
 for(float v:{.2f,.5f,.96f,.985f,.99f,.999f,1.f,2.f}){auto c=pbr({v,v,v});assert(std::isfinite(c.r)&&c.r<1.f&&c.r==c.g&&c.g==c.b);}
 // The production classifier must keep bright rectangles insufficient on their own.
 using bncam::tone::defaultRawSceneEvidence;
 assert(defaultRawSceneEvidence(100,2,true,false).displayGate==0.f);
 assert(defaultRawSceneEvidence(800,25,true,false).displayGate>.7f);
 assert(defaultRawSceneEvidence(800,25,true,true).displayGate==0.f);
 assert(defaultRawSceneEvidence(100,0,false,false).displayGate==0.f);
 // Zero controls are exact identity even for high-frequency coloured textures.
 NativeRenderQualityConfig neutral;
 neutral.profileColorSaturation=0;neutral.profilePresenceVibrance=0;neutral.profileColorContrast=0;
 for(int i=0;i<1000;++i){float r=.1f+.0001f*i,g=.7f-.0003f*i,b=.02f+.0005f*i;const Rgb before{r,g,b};applyBncamProfileColorManagement(r,g,b,neutral,false);assert(r==before.r&&g==before.g&&b==before.b);}
 // Exact RGGB WB must retain unequal green gains even beyond the legacy ratio
 // envelope; metadata ownership is pointwise and must not learn a residual split.
 std::fill(source.p,source.p+N,.2f);
 Push green;green.u[0]=W;green.u[1]=H;green.u[6]=1;green.f(14,2);
 green.f(15,1);green.f(16,1);green.f(17,4);green.u[27]=3;
 std::memset(telemetry.p,0,telemetry.bytes);
 gpu.run(dir+"/spectra_raw_finalize.comp.spv",{&source,&finalized,&empty,&telemetry,&empty},green.u.data(),112);
 for(unsigned y=0;y<H;++y)for(unsigned x=0;x<W;++x){
  float expected=(y%2==0 && x%2==1)?.32f:(y%2==1 && x%2==0)?.08f:.2f;
  assert(std::abs(finalized.p[y*W+x]-expected)<1e-7f);
 }
 std::cout<<"exact unequal Camera2 greens preserved: passed\n";
 // Compare the session frozen accepted baseline: shared Spectra/YUV behavior stays exact.
 for(const char* stage:{"spectra_raw_finalize.comp","spectra_demosaic_resident.comp","spectra_tone_resident.comp","yuv_shared"}){
  std::vector<float> previous;
  for(int baseline=0;baseline<2;++baseline){std::memset(telemetry.p,0,telemetry.bytes);std::string file=dir+"/"+(baseline?"head-":"")+(std::string(stage)=="yuv_shared"?"spectra_tone_resident.comp":stage)+".spv";
   if(std::string(stage).find("raw_finalize")!=std::string::npos){Push p;p.u[0]=W;p.u[1]=H;p.u[6]=1;p.f(14,2);p.f(15,1);p.f(16,1);p.f(17,1);gpu.run(file,{&source,&finalized,&empty,&telemetry,&empty},p.u.data(),112);if(!baseline)previous.assign(finalized.p,finalized.p+N+N/4);else assert(error(previous,finalized.p)==0);}
   else if(std::string(stage).find("demosaic")!=std::string::npos){Push p;p.u[0]=W;p.u[1]=H;p.u[3]=3;p.f(4,2);p.f(5,1);p.f(6,1.6f);p.f(8,1);p.f(12,1);p.f(16,1);p.f(28,1);gpu.run(file,{&finalized,&rgb,&color,&stats,&empty,&empty,&telemetry,&empty},p.u.data(),128,N*4);if(!baseline)previous.assign(color.p,color.p+N*3);else assert(error(previous,color.p)==0);}
   else{std::memcpy(tone.p,color.p,N*3*4);Push p;p.u[0]=W;p.u[1]=H;p.u[2]=3;p.u[6]=std::string(stage)=="yuv_shared"?0u:1u;p.f(8,1);p.f(9,1);p.f(14,.68f);p.f(15,1);gpu.run(file,{&color,&tone,&empty,&lut,&telemetry,&empty,&empty,&empty,&empty,&empty,&empty,&empty,&empty},p.u.data(),120);if(!baseline)previous.assign(tone.p,tone.p+N*3);else assert(error(previous,tone.p)==0);}
  }
  std::cout<<"legacy bit identity "<<stage<<" passed\n";
 }
 std::cout<<"max_map_error="<<maxMapError<<" max_color_error="<<maxColorError<<" max_tone_error="<<maxToneError<<" preserved_pixels="<<partialUnchanged<<" fully_neutral_pixels="<<fullNeutral<<"\n";
 std::cout<<"ALL PASSED\n";
}
