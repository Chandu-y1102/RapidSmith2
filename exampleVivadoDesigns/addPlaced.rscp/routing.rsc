SITE_PIPS U13 OUSED:0 
SITE_PIPS T13 OUSED:0 
SITE_PIPS T10 IUSED:0 IBUFDISABLE_SEL:GND INTERMDISABLE_SEL:GND 
SITE_PIPS T9 IUSED:0 IBUFDISABLE_SEL:GND INTERMDISABLE_SEL:GND 
SITE_PIPS SLICE_X0Y51 AUSED:0 AOUTMUX:O5 
SITE_PIPS R10 IUSED:0 IBUFDISABLE_SEL:GND INTERMDISABLE_SEL:GND 
VCC_SOURCES 
GND_SOURCES 
LUT_RTS 
INTRASITE a
INTERSITE a_IBUF SLICE_X0Y51/A4 R10/I 
INTRASITE b
INTERSITE b_IBUF T10/I SLICE_X0Y51/A5 
INTRASITE cin
INTERSITE cin_IBUF T9/I SLICE_X0Y51/A3 
INTRASITE cout
INTERSITE cout_OBUF SLICE_X0Y51/AMUX U13/O 
INTRASITE s
INTERSITE s_OBUF SLICE_X0Y51/A T13/O 
